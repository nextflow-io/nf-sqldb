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

package nextflow.sql

import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.sql.config.SqlDataSource

/**
 * Keep at most one idle connection per data source, so that repeated
 * calls to the SQL functions don't open a new connection each time.
 *
 * A call takes the idle connection if there is one, otherwise it opens
 * a new connection. When done, the connection is kept if no other
 * connection is idle, otherwise it is closed.
 */
@Slf4j
@CompileStatic
class ConnectionCache {

    static final long VALIDATE_AFTER_MS = TimeUnit.SECONDS.toMillis(30)

    static final int VALIDATE_TIMEOUT_SECS = 10

    private final Map<SqlDataSource,AtomicReference<IdleConnection>> idle = new ConcurrentHashMap<>()

    // idle time after which a connection is validated before reuse
    private final long validateAfterMs

    private volatile boolean closed

    ConnectionCache(long validateAfterMs = VALIDATE_AFTER_MS) {
        this.validateAfterMs = validateAfterMs
    }

    @CompileStatic
    private static class IdleConnection {
        final Connection conn
        final long since = System.currentTimeMillis()

        IdleConnection(Connection conn) { this.conn = conn }
    }

    /**
     * Run an action with a connection to the given data source. The
     * connection is closed instead of kept if the action fails or leaves
     * autocommit disabled.
     */
    def <T> T withConnection(SqlDataSource dataSource, Closure<T> action) {
        if( !isCacheable(dataSource.url) ) {
            try( Connection conn = connect(dataSource) ) {
                return action.call(conn)
            }
        }
        final slot = idle.computeIfAbsent(dataSource) { new AtomicReference<IdleConnection>() }
        final conn = take(slot) ?: connect(dataSource)
        boolean reusable = false
        try {
            final result = action.call(conn)
            reusable = isAutoCommit(conn)
            return result
        }
        finally {
            if( !reusable || closed || !slot.compareAndSet(null, new IdleConnection(conn)) )
                closeQuietly(conn)
            // the cache was closed while the connection was put back
            else if( closed )
                closeQuietly(slot.getAndSet(null)?.conn)
        }
    }

    void close() {
        closed = true
        for( final slot : idle.values() )
            closeQuietly(slot.getAndSet(null)?.conn)
    }

    private Connection take(AtomicReference<IdleConnection> slot) {
        final entry = slot.getAndSet(null)
        if( entry == null )
            return null
        if( !isClosed(entry.conn) && (System.currentTimeMillis() - entry.since < validateAfterMs || isValid(entry.conn)) )
            return entry.conn
        log.debug "[SQL] idle connection is no longer valid, opening a new connection"
        closeQuietly(entry.conn)
        return null
    }

    @PackageScope
    Connection getIdle(SqlDataSource dataSource) {
        return idle.get(dataSource)?.get()?.conn
    }

    protected Connection connect(SqlDataSource dataSource) {
        return Sql.newInstance(dataSource.toMap()).getConnection()
    }

    /**
     * Embedded file databases are not cached, so that the file isn't kept
     * locked between calls, nor private in-memory databases, since each
     * connection has its own database.
     */
    static boolean isCacheable(String url) {
        final lower = url?.toLowerCase() ?: ''
        if( lower.startsWith('jdbc:duckdb:') ) {
            final path = lower.substring('jdbc:duckdb:'.length())
            return path.startsWith('md:') || path.startsWith('motherduck:') || (path.startsWith(':memory:') && path != ':memory:')
        }
        if( lower.startsWith('jdbc:h2:') ) {
            final path = lower.substring('jdbc:h2:'.length())
            return path.startsWith('tcp:') || path.startsWith('ssl:') || (path.startsWith('mem:') && path != 'mem:' && !path.startsWith('mem:;'))
        }
        if( lower.startsWith('jdbc:sqlite:') ) {
            // query parameters only apply to URI file names
            final path = lower.substring('jdbc:sqlite:'.length())
            final memory = path.startsWith('file::memory:') || (path.startsWith('file:') && path.contains('mode=memory'))
            return memory && path.contains('cache=shared')
        }
        return true
    }

    /**
     * Check whether a connection is in autocommit mode, assuming it is
     * if the driver doesn't support transactions (e.g. Databricks).
     */
    private static boolean isAutoCommit(Connection conn) {
        try {
            return conn.getAutoCommit()
        }
        catch( SQLFeatureNotSupportedException | AbstractMethodError e ) {
            return true
        }
        catch( SQLException e ) {
            return false
        }
    }

    private static boolean isClosed(Connection conn) {
        try {
            return conn.isClosed()
        }
        catch( SQLException e ) {
            return true
        }
    }

    // assume the connection is valid if the driver can't tell
    private static boolean isValid(Connection conn) {
        try {
            return conn.isValid(VALIDATE_TIMEOUT_SECS)
        }
        catch( SQLFeatureNotSupportedException | AbstractMethodError e ) {
            return true
        }
        catch( SQLException e ) {
            return false
        }
    }

    private static void closeQuietly(Connection conn) {
        try {
            conn?.close()
        }
        catch( Exception e ) {
            log.debug "[SQL] unable to close connection - cause: ${e.message}"
        }
    }
}
