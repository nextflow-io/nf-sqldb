/*
 * Copyright 2020-2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package nextflow.sql.config

import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier

import groovy.sql.Sql
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import nextflow.extension.Bolts
import nextflow.util.Duration

/**
 * Model a dataSource configuration
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@ToString(includePackage = false, includeNames = true)
@EqualsAndHashCode
class SqlDataSource {
    public static String DEFAULT_URL = 'jdbc:h2:mem:'
    public static String DEFAULT_DRIVER = 'org.h2.Driver'
    public static String DEFAULT_USER = 'sa'

    static SqlDataSource DEFAULT = new SqlDataSource(Collections.emptyMap())

    String driver
    String url
    String user
    String password
    Duration timeout

    SqlDataSource(Map opts) {
        this.url = opts.url ?: DEFAULT_URL
        this.driver = opts.driver ?: urlToDriver(url) ?: DEFAULT_DRIVER
        this.user = resolveCredential(opts.user, 'user') ?: DEFAULT_USER
        this.password = resolveCredential(opts.password, 'password')
        this.timeout = resolveTimeout(opts.timeout)
    }

    SqlDataSource(Map opts, SqlDataSource fallback) {
        this.url = opts.url ?: fallback.url ?: DEFAULT_URL
        this.driver = opts.driver ?: urlToDriver(url) ?: fallback.driver ?: DEFAULT_DRIVER
        this.user = resolveCredential(opts.user, 'user') ?: fallback.user ?: DEFAULT_USER
        this.password = resolveCredential(opts.password, 'password') ?: fallback.password
        this.timeout = resolveTimeout(opts.timeout) ?: fallback.timeout
    }

    /**
     * Parse the optional connection timeout, given either as a duration string,
     * a {@link Duration} object or a number of seconds.
     *
     * @param value The timeout value from configuration, or null
     * @return The corresponding {@link Duration} or null when not specified
     */
    protected Duration resolveTimeout(Object value) {
        if( value == null )
            return null
        if( value instanceof Duration )
            return value
        if( value instanceof Number )
            return Duration.of(Math.round(value.doubleValue() * 1_000) as long)
        final str = value.toString().trim()
        if( !str )
            return null
        return str.isLong()
            ? Duration.of(str.toLong() * 1_000)
            : new Duration(str)
    }

    /**
     * @return The connection timeout in seconds, rounded up so any non-zero
     *      sub-second value is never truncated away to zero. Zero itself means
     *      "no timeout" and is returned unchanged. Only used for reporting/tests;
     *      the actual bound is enforced with nanosecond precision.
     */
    Integer getTimeoutSecs() {
        if( timeout == null )
            return null
        final millis = timeout.toMillis()
        return millis <= 0 ? 0 : Math.max(1, (int) Math.ceil(millis / 1_000.0d))
    }

    // Caps the number of connect attempts that are still running on an abandoned
    // daemon thread after their caller already gave up on them (see connectWithHardBound).
    // Without a cap, a persistent outage would accumulate one leaked stuck thread (and
    // potentially an unclosable connection) per timed-out attempt, without bound.
    public static final int MAX_ABANDONED_CONNECT_ATTEMPTS = 16
    private static final Semaphore ABANDONED_CONNECT_SLOTS = new Semaphore(MAX_ABANDONED_CONNECT_ATTEMPTS)

    /**
     * Open a new connection for this data source, bounding the time spent
     * establishing it when a timeout is configured.
     *
     * @return A new JDBC {@link Connection}
     */
    Connection connect() {
        final timeoutSecs = getTimeoutSecs()
        if( timeoutSecs == null || timeoutSecs == 0 ) {
            // unconfigured, or explicitly configured as zero: zero means "no timeout",
            // not "immediate failure" -- run the connect with no bound, same as before
            // this feature existed
            return Sql.newInstance(toMap()).getConnection()
        }
        // Use nanosecond precision throughout so a sub-second configured timeout (e.g. '100ms')
        // cannot be inflated by rounding to whole seconds.
        final timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeout.toMillis())
        return connectWithHardBound(timeoutNanos, timeout)
    }

    private static final AtomicLong CONNECT_THREAD_COUNTER = new AtomicLong()

    /**
     * Run the actual connection attempt on a background daemon thread and enforce
     * the configured bound regardless of whether the underlying driver cooperates.
     * {@code DriverManager#setLoginTimeout(int)} is intentionally not used: it is a
     * JVM-global setting, so mutating it here could affect unrelated JDBC connects
     * elsewhere in the same JVM, and it is advisory only -- plenty of drivers (e.g.
     * trino-jdbc) never consult it anyway. This hard bound works independently of
     * driver cooperation.
     *
     * @param remainingNanos time left to wait, in nanoseconds
     * @param configuredTimeout the originally configured timeout, for error messages only
     */
    private Connection connectWithHardBound(long remainingNanos, Duration configuredTimeout) {
        if( !ABANDONED_CONNECT_SLOTS.tryAcquire() )
            throw new SQLTimeoutException(
                "Too many connect attempts to datasource '$url' are already stuck past their configured " +
                "timeout (limit: $MAX_ABANDONED_CONNECT_ATTEMPTS) -- refusing to start another; " +
                "previous connection attempts to this datasource appear to still be stuck"
            )
        boolean abandoned = false
        final executor = Executors.newSingleThreadExecutor { Runnable r ->
            final t = new Thread(r, "sql-connect-${CONNECT_THREAD_COUNTER.incrementAndGet()}")
            t.daemon = true
            return t
        }
        try {
            final future = CompletableFuture.supplyAsync({ Sql.newInstance(toMap()).getConnection() } as Supplier<Connection>, executor)
            try {
                return future.get(Math.max(0L, remainingNanos), TimeUnit.NANOSECONDS)
            }
            catch( TimeoutException e ) {
                // the connect attempt may still complete (successfully) after we give up on
                // it: attach whenComplete on the *original* future (not a cancelled one --
                // CompletableFuture#cancel ignores mayInterruptIfRunning and would otherwise
                // make whenComplete observe a CancellationException instead of the real,
                // later result) so a late-arriving connection is closed instead of leaked,
                // and the reserved slot is released either way.
                abandoned = true
                future.whenComplete { conn, err ->
                    try {
                        if( conn != null )
                            try { conn.close() } catch( Exception ignored ) {}
                    }
                    finally {
                        ABANDONED_CONNECT_SLOTS.release()
                    }
                }
                throw new SQLTimeoutException("Timed out connecting to datasource '$url' after ${configuredTimeout}")
            }
            catch( ExecutionException e ) {
                final cause = e.cause
                throw cause instanceof SQLException ? cause : new SQLException("Failed to connect to datasource '$url'", cause)
            }
        }
        finally {
            // shutdownNow does not guarantee the connect attempt is interrupted if the
            // driver is blocked in uninterruptible native I/O; the daemon thread may
            // outlive this call, but it can never prevent JVM shutdown.
            executor.shutdownNow()
            if( !abandoned )
                ABANDONED_CONNECT_SLOTS.release()
        }
    }


    protected String urlToDriver(String url) {
        DriverRegistry.DEFAULT.urlToDriver(url)
    }

    /**
     * Resolves a credential value, checking for unresolved secrets and providing appropriate error handling
     * 
     * @param value The credential value from configuration
     * @param credType The type of credential ('user' or 'password') for error messages
     * @return The resolved credential value, or null if not provided
     * @throws IllegalArgumentException if an unresolved secret is detected
     */
    protected String resolveCredential(Object value, String credType) {
        if (value == null) {
            return null
        }
        
        String stringValue = value.toString()
        
        // Check for unresolved secrets (patterns like 'secrets.ATHENA_USER' or similar)
        if (stringValue.startsWith('secrets.') || stringValue.contains('secret') && stringValue.contains('[') && stringValue.contains(']')) {
            throw new IllegalArgumentException(
                "Unresolved secret detected for $credType: '$stringValue'. " +
                "This typically indicates that workspace secrets are not properly configured or accessible. " +
                "Please verify that:\n" +
                "1. The secret is defined in your workspace/user secrets\n" +
                "2. The secret name matches exactly (case-sensitive)\n" +
                "3. You have proper permissions to access the secret\n" +
                "4. The Nextflow version supports secrets integration (>=25.04.0)\n" +
                "See: https://www.nextflow.io/docs/latest/secrets.html"
            )
        }
        
        return stringValue.isEmpty() ? null : stringValue
    }

    Map toMap() {
        final result = new HashMap(10)
        if( url )
            result.url = url
        if( driver )
            result.driver = driver
        if( user || password ) {
            result.user = user
            result.password = password
        }
        return result
    }

    @Override
    String toString() {
        return "SqlDataSource[url=$url; driver=$driver; user=$user; password=${Bolts.redact(password)}; timeout=$timeout]"
    }
}
