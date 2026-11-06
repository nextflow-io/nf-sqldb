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
import java.sql.SQLTransientConnectionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

import groovy.sql.Sql
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.sql.config.SqlDataSource
import nextflow.sql.shaded.hikari.HikariConfig
import nextflow.sql.shaded.hikari.HikariDataSource
import nextflow.sql.shaded.hikari.SQLExceptionOverride
import nextflow.sql.shaded.hikari.pool.HikariPool
import nextflow.util.Duration
import org.slf4j.LoggerFactory

/**
 * Connection pools for the databases used by the SQL functions,
 * one per data source, created on first use and closed at the end
 * of the run.
 */
@Slf4j
@CompileStatic
class ConnectionPools {

    static final int DEFAULT_MAX_POOL_SIZE = 10

    // generous, since opening a connection to a stopped warehouse (e.g. Databricks) can take minutes
    static final long CONNECTION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5)

    static {
        quietPoolLogs()
    }

    private final Map<SqlDataSource,PoolHolder> pools = new ConcurrentHashMap<>()

    private final long connectionTimeout

    private volatile boolean closed

    ConnectionPools(long connectionTimeout = CONNECTION_TIMEOUT_MS) {
        this.connectionTimeout = connectionTimeout
    }

    /**
     * Run an action with a connection borrowed from the pool of the given
     * data source. The connection is returned to the pool afterwards, or
     * evicted if the action failed.
     *
     * @param name the database name, for the pool name and error messages
     */
    def <T> T withConnection(SqlDataSource dataSource, String name, Closure<T> action) {
        // pooling is disabled for this database, or the run is over
        final pool = isPooled(dataSource) ? getOrCreatePool(dataSource, name) : null
        if( pool == null ) {
            try( Connection conn = connect(dataSource) ) {
                return action.call(conn)
            }
        }
        final conn = borrow(pool, dataSource, name)
        boolean failed = true
        try {
            final result = action.call(conn)
            failed = false
            return result
        }
        finally {
            release(pool, conn, failed)
        }
    }

    /**
     * Private in-memory databases are not pooled, since each connection has
     * its own database. Embedded file databases are locked while a connection
     * is open, so they are pooled only if `maxPoolSize` is set.
     */
    private static boolean isPooled(SqlDataSource dataSource) {
        if( isPrivateMemory(dataSource.url) )
            return false
        if( dataSource.maxPoolSize == null )
            return !isEmbeddedFile(dataSource.url)
        return dataSource.maxPoolSize > 0
    }

    @PackageScope
    HikariDataSource getPool(SqlDataSource dataSource) {
        final holder = pools.get(dataSource)
        return holder != null ? holder.pool : null
    }

    /**
     * Get the pool for a data source, or null if the pools are closed or
     * the driver doesn't support pooling.
     * Pools are created under a per-database lock, since opening the first
     * connection can take minutes (e.g. a stopped warehouse).
     */
    private HikariDataSource getOrCreatePool(SqlDataSource dataSource, String name) {
        if( closed )
            return null
        final holder = pools.computeIfAbsent(dataSource) { new PoolHolder() }
        return holder.getOrCreate { createPool(dataSource, name) }
    }

    void close() {
        closed = true
        for( final dataSource : pools.keySet() ) {
            final holder = pools.remove(dataSource)
            try {
                holder?.close()
            }
            catch( Exception e ) {
                log.debug "Unable to close connection pool for database: ${dataSource.url} - cause: ${e.message}"
            }
        }
    }

    private static class PoolHolder {

        private volatile HikariDataSource pool

        // the pool was created (or not supported) or closed
        private volatile boolean done

        HikariDataSource getOrCreate(Closure<HikariDataSource> factory) {
            if( done )
                return pool
            synchronized (this) {
                if( !done ) {
                    pool = factory.call()
                    done = true
                }
                return pool
            }
        }

        synchronized void close() {
            done = true
            pool?.close()
            pool = null
        }
    }

    private HikariDataSource createPool(SqlDataSource dataSource, String name) {
        final config = new HikariConfig()
        config.setPoolName("nf-sqldb-${name}".toString())
        config.setDataSource(driverDataSource(dataSource))
        config.setMaximumPoolSize(maxPoolSize(dataSource))
        // open connections only when needed, and close them after the idle timeout
        config.setMinimumIdle(0)
        // don't query idle connections in the background, which would keep warehouses running;
        // they are validated when borrowed instead
        config.setKeepaliveTime(0)
        config.setConnectionTimeout(connectionTimeout)
        // don't close idle connections to shared in-memory databases, which are dropped with their last connection
        if( isMemory(dataSource.url) ) {
            config.setIdleTimeout(0)
            config.setMaxLifetime(0)
        }
        config.setExceptionOverride(new FeatureNotSupportedOverride())
        try {
            return new HikariDataSource(config)
        }
        catch( HikariPool.PoolInitializationException e ) {
            final cause = rootCause(e)
            // the driver doesn't support a method used by the pool (e.g. isValid);
            // a genuine connection error is raised again by the unpooled call
            if( cause instanceof SQLFeatureNotSupportedException || cause instanceof AbstractMethodError ) {
                log.debug "[SQL] connection pooling is not supported by the driver for database '${name}', opening a connection for each call - cause: ${cause}"
                return null
            }
            // report the cause of the connection failure, as without pooling
            throw cause
        }
    }

    private static int maxPoolSize(SqlDataSource dataSource) {
        return dataSource.maxPoolSize ?: DEFAULT_MAX_POOL_SIZE
    }

    /**
     * Check whether a JDBC URL is an embedded database stored in a file,
     * which is locked while a connection is open.
     */
    static boolean isEmbeddedFile(String url) {
        if( isMemory(url) )
            return false
        final lower = url?.toLowerCase() ?: ''
        if( lower.startsWith('jdbc:duckdb:') ) {
            final path = lower.substring('jdbc:duckdb:'.length())
            return !path.startsWith('md:') && !path.startsWith('motherduck:')
        }
        if( lower.startsWith('jdbc:h2:') ) {
            final path = lower.substring('jdbc:h2:'.length())
            return path && !path.startsWith('tcp:') && !path.startsWith('ssl:')
        }
        return lower.startsWith('jdbc:sqlite:')
    }

    /**
     * Check whether a JDBC URL is an embedded in-memory database, which is
     * dropped when its last connection is closed (unless H2 DB_CLOSE_DELAY=-1).
     */
    static boolean isMemory(String url) {
        final lower = url?.toLowerCase() ?: ''
        if( lower.startsWith('jdbc:duckdb:') ) {
            final path = lower.substring('jdbc:duckdb:'.length())
            return !path || path.startsWith(':memory:')
        }
        if( lower.startsWith('jdbc:h2:') )
            return lower.startsWith('jdbc:h2:mem:')
        if( lower.startsWith('jdbc:sqlite:') ) {
            // query parameters only apply to URI file names, otherwise they are part of the file name
            final path = lower.substring('jdbc:sqlite:'.length())
            return !path || path == ':memory:' || path.startsWith('file::memory:') || (path.startsWith('file:') && path.contains('mode=memory'))
        }
        return false
    }

    /**
     * Check whether a JDBC URL is an in-memory database that is private
     * to each connection, i.e. unnamed (DuckDB, H2) or without shared
     * cache (SQLite).
     */
    static boolean isPrivateMemory(String url) {
        if( !isMemory(url) )
            return false
        final lower = url.toLowerCase()
        if( lower.startsWith('jdbc:duckdb:') ) {
            final path = lower.substring('jdbc:duckdb:'.length())
            return !path || path == ':memory:'
        }
        if( lower.startsWith('jdbc:h2:') ) {
            final name = lower.substring('jdbc:h2:mem:'.length())
            return !name || name.startsWith(';')
        }
        return !lower.contains('cache=shared')
    }

    /**
     * Log only errors from the pools, if the logging backend is Logback
     * (as in Nextflow), since the pool logs its activity in detail.
     */
    private static void quietPoolLogs() {
        try {
            final logger = LoggerFactory.getLogger(HikariConfig.package.name)
            if( logger.class.name == 'ch.qos.logback.classic.Logger' )
                setLevelError(logger)
        }
        catch( Throwable e ) {
            log.debug "[SQL] unable to set the connection pool log level - cause: ${e.message}"
        }
    }

    @CompileDynamic
    private static void setLevelError(Object logger) {
        logger.level = logger.class.classLoader.loadClass('ch.qos.logback.classic.Level').toLevel('ERROR')
    }

    private Connection borrow(HikariDataSource pool, SqlDataSource dataSource, String name) {
        try {
            return pool.getConnection()
        }
        catch( SQLTransientConnectionException e ) {
            final timeout = Duration.of(connectionTimeout)
            final msg = e.cause != null
                ? "Unable to connect to database '${name}' within ${timeout} - cause: ${rootCause(e.cause).message}"
                : "Timed out after ${timeout} waiting for a connection to database '${name}' - all ${maxPoolSize(dataSource)} connections are in use (see the `sql.db.<name>.maxPoolSize` config option)"
            throw new SQLTransientConnectionException(msg, e.getSQLState(), e)
        }
    }

    /**
     * Return a connection to the pool, or evict it if the call failed (the
     * connection may be broken) or left a transaction open with SQL statements
     * (e.g. `BEGIN`). The pool restores autocommit and rolls back a transaction
     * started with JDBC calls on return.
     */
    private static void release(HikariDataSource pool, Connection conn, boolean failed) {
        if( failed || inTransaction(conn) ) {
            pool.evictConnection(conn)
            return
        }
        try {
            conn.close()
        }
        catch( SQLException e ) {
            log.debug "[SQL] unable to return connection to the pool - cause: ${e.message}"
            pool.evictConnection(conn)
        }
    }

    /**
     * Check whether a connection is in a transaction that the pool doesn't
     * know about, assuming it isn't if the driver can't tell.
     */
    private static boolean inTransaction(Connection conn) {
        try {
            final driverConn = conn.unwrap(Connection)
            return !driverConn.getAutoCommit() || isPostgresTransaction(driverConn)
        }
        catch( SQLException | AbstractMethodError e ) {
            return false
        }
    }

    /**
     * Check whether a pgjdbc connection is in a transaction, since a transaction
     * started with `BEGIN` doesn't change the autocommit mode. The driver isn't
     * referenced directly, since plugins may bundle nf-sqldb without it.
     */
    @CompileDynamic
    private static boolean isPostgresTransaction(Connection conn) {
        return conn.class.name == 'org.postgresql.jdbc.PgConnection' && conn.transactionState.toString() != 'IDLE'
    }

    private static Throwable rootCause(Throwable e) {
        Throwable result = e
        while( result.cause != null && result.cause != result && result.class.name.startsWith(HikariConfig.package.name) )
            result = result.cause
        return result
    }

    /**
     * Keep connections that throw {@link SQLFeatureNotSupportedException},
     * which the pool would otherwise evict as broken (SQLSTATE 0A000), e.g.
     * on {@code setAutoCommit(false)} with drivers that don't support
     * transactions.
     */
    private static class FeatureNotSupportedOverride implements SQLExceptionOverride {
        SQLExceptionOverride.Override adjudicate(SQLException e) {
            return e instanceof SQLFeatureNotSupportedException
                ? SQLExceptionOverride.Override.DO_NOT_EVICT
                : SQLExceptionOverride.Override.CONTINUE_EVICT
        }
    }

    private static Connection connect(SqlDataSource dataSource) {
        return Sql.newInstance(dataSource.toMap()).getConnection()
    }

    /**
     * Open connections the same way as the operators, so that drivers are
     * loaded as before (including drivers registered by other plugins).
     * The login timeout is read by the pool on shutdown.
     */
    private static DataSource driverDataSource(SqlDataSource dataSource) {
        return [getConnection: { -> connect(dataSource) }, getLoginTimeout: { -> 0 }] as DataSource
    }
}
