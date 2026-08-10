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
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.util.concurrent.CompletableFuture

import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.Channel
import nextflow.Global
import nextflow.Session
import nextflow.sql.config.SqlDataSource
/**
 * Implement the logic for query a DB in async manner
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class QueryHandler implements QueryOp<QueryHandler> {

    private static Map<String,Class<?>> type_mapping = [:]

    static {
        type_mapping.CHAR = String
        type_mapping.VARCHAR = String
        type_mapping.LONGVARCHAR = String
        type_mapping.NUMERIC = BigDecimal
        type_mapping.DECIMAL = BigDecimal
        type_mapping.BIT = Boolean
        type_mapping.TINYINT = Byte
        type_mapping.SMALLINT = Short
        type_mapping.INTEGER = Integer
        type_mapping.BIGINT	= Long
        type_mapping.REAL= Float
        type_mapping.FLOAT= Double
        type_mapping.DOUBLE	= Double
        type_mapping.BINARY	= byte[]
        type_mapping.VARBINARY = byte[]
        type_mapping.LONGVARBINARY= byte[]
        type_mapping.DATE = java.sql.Date
        type_mapping.TIME = java.sql.Time
        type_mapping.TIMESTAMP= java.sql.Timestamp
    }

    private DataflowWriteChannel target
    private String statement
    private SqlDataSource dataSource
    private boolean emitColumns = false
    private Integer batchSize
    private long batchDelayMillis = 100
    private int queryCount

    private final Object lock = new Object()
    private Statement activeStatement
    private boolean cancelRequested

    @Override
    QueryOp withStatement(String stm) {
        this.statement = stm
        return this
    }

    @Override
    QueryOp withTarget(DataflowWriteChannel channel) {
        this.target = channel
        return this
    }

    @Override
    QueryOp withDataSource(SqlDataSource datasource) {
        this.dataSource = datasource
        return this
    }

    QueryOp withOpts(Map opts) {
        if( opts.emitColumns )
            this.emitColumns = opts.emitColumns as boolean
        if( opts.batchSize )
            this.batchSize = opts.batchSize as Integer
        if( opts.batchDelay )
            this.batchDelayMillis = opts.batchDelay as long
        return this
    }

    int batchSize() {
        return batchSize
    }

    int queryCount() {
        return queryCount
    }

    @Override
    QueryHandler perform(boolean async=false) {
        final conn = connect(dataSource ?: SqlDataSource.DEFAULT)
        if( async )
            queryAsync(conn)
        else
            queryExec(conn)
        return this
    }

    protected Connection connect(SqlDataSource ds) {
        log.debug "Creating SQL connection: ${ds}"
        Sql.newInstance(ds.toMap()).getConnection()
    }

    protected String normalize(String q) {
        if( !q )
            throw new IllegalArgumentException("Missing query argument")
        def result = q.trim()
        if( !result.endsWith(';') )
            result += ';'
        return result
    }

    protected queryAsync(Connection conn) {
        registerAbortHook()
        def future = CompletableFuture.runAsync ({ queryExec(conn) })
        future.exceptionally(this.&handlerException)
    }

    private void registerAbortHook() {
        final session = Global.session as Session
        session?.onShutdown {
            if( session.isAborted() )
                cancel()
        }
    }

    /**
     * Cancel the statement currently executing this query, if any.
     * Safe to call concurrently with query execution: if no statement
     * is active yet, the cancellation is recorded and applied as soon
     * as one is created. The actual driver {@code cancel()} call is made
     * outside the lock so a stalling driver cannot block track/untrack,
     * and any {@code SQLException} it raises (e.g. the statement was
     * already closed by the time cancel runs) is logged, not propagated.
     */
    void cancel() {
        Statement stm
        synchronized (lock) {
            cancelRequested = true
            stm = activeStatement
        }
        if( stm == null )
            return
        try {
            stm.cancel()
        }
        catch( java.sql.SQLException e ) {
            log.debug "Unable to cancel in-flight SQL statement: ${e.message}"
        }
    }

    private boolean isCancelled() {
        synchronized (lock) {
            return cancelRequested
        }
    }

    /**
     * Track the statement about to execute this query. Returns {@code true}
     * if cancellation was already requested before the statement existed,
     * in which case the caller must not execute it: a cancelled-but-idle
     * JDBC statement is not guaranteed to interrupt a subsequent execute.
     */
    private boolean trackStatement(Statement stm) {
        synchronized (lock) {
            if( cancelRequested )
                return true
            activeStatement = stm
            return false
        }
    }

    private void untrackStatement() {
        synchronized (lock) {
            activeStatement = null
        }
    }

    private void handlerException(Throwable e) {
        final error = e.cause ?: e
        if( isCancelled() ) {
            log.debug "SQL query cancelled: ${error.message}"
            return
        }
        log.error(error.message, error)
        final session = Global.session as Session
        session?.abort(error)
    }

    protected void queryExec(Connection conn) {
        if( batchSize ) {
            query1(conn)
        }
        else {
            query0(conn)
        }
    }

    protected void query0(Connection conn) {
        try {
            final Statement stm = conn.createStatement()
            try {
                if( trackStatement(stm) || isCancelled() ) {
                    target.bind(Channel.STOP)
                    return
                }
                final String normalizedStmt = normalize(statement)
                // Execute the SQL query and get results
                try (def rs = stm.executeQuery(normalizedStmt)) {
                    if (emitColumns)
                        emitColumns(rs)
                    emitRowsAndClose(rs)
                }
            }
            finally {
                untrackStatement()
                stm.close()
            }
        }
        finally {
            conn.close()
        }
    }

    protected void query1(Connection conn) {
        try {
            // create the query adding the `offset` and `limit` params
            final query = makePaginationStm(statement)
            // create the prepared statement
            final PreparedStatement stm = conn.prepareStatement(query)
            try {
                if( !trackStatement(stm) ) {
                    int count = 0
                    int len = 0
                    while( count==0 || len==batchSize ) {
                        final offset = (count++) * batchSize
                        final limit = batchSize

                        stm.setInt(1, limit)
                        stm.setInt(2, offset)
                        if( isCancelled() )
                            break
                        queryCount++
                        try ( def rs = stm.executeQuery() ) {
                            if( emitColumns && count==1 )
                                emitColumns(rs)
                            len = emitRows(rs)
                            sleep(batchDelayMillis)
                        }
                    }
                }
            }
            finally {
                untrackStatement()
                stm.close()
                // close the channel
                target.bind(Channel.STOP)
            }
        }
        finally {
            conn.close()
        }
    }

    protected String makePaginationStm(String sql) {
        if( sql.toUpperCase().contains('LIMIT') )
                throw new IllegalArgumentException("Sql query should not include the LIMIT statement when pageSize is specified: $sql")
        if( sql.toUpperCase().contains('OFFSET') )
            throw new IllegalArgumentException("Sql query should not include the OFFSET statement when pageSize is specified: $sql")

        return sql.stripEnd(' ;') + " LIMIT ? OFFSET ?;"
    }

    protected emitColumns(ResultSet rs) {
        final meta = rs.getMetaData()
        final cols = meta.getColumnCount()

        def item = new ArrayList(cols)
        for( int i=0; i<cols; i++) {
            item[i] = meta.getColumnName(i+1)
        }
        // emit the value
        target.bind(item)
    }

    protected int emitRows(ResultSet rs) {
        final meta = rs.getMetaData()
        final cols = meta.getColumnCount()

        int count=0
        while( rs.next() ) {
            count++
            def item = new ArrayList(cols)
            for( int i=0; i<cols; i++) {
                item[i] = rs.getObject(i+1)
            }
            // emit the value
            target.bind(item)
        }
        return count
    }

    protected int emitRowsAndClose(ResultSet rs) {
        try {
            emitRows(rs)
        }
        finally {
            // close the channel
            target.bind(Channel.STOP)
        }
    }
}
