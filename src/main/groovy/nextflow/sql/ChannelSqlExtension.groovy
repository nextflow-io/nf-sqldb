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


import groovy.sql.BatchingPreparedStatementWrapper
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.DataflowWriteChannel
import groovyx.gpars.dataflow.expression.DataflowExpression
import nextflow.Channel
import nextflow.Global
import nextflow.Session
import nextflow.extension.CH
import nextflow.extension.DataflowHelper
import nextflow.plugin.extension.Factory
import nextflow.plugin.extension.Function
import nextflow.plugin.extension.Operator
import nextflow.plugin.extension.PluginExtensionPoint
import nextflow.script.dsl.Description
import nextflow.sql.config.SqlConfig
import nextflow.sql.config.SqlDataSource
import nextflow.util.CheckHelper
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.Statement
import java.util.concurrent.ConcurrentHashMap
import groovy.sql.Sql
/**
 * Provide a channel factory extension that allows the execution of Sql queries
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class ChannelSqlExtension extends PluginExtensionPoint {

    private static final Map QUERY_PARAMS = [
            db: CharSequence,
            emitColumns: Boolean,
            batchSize: Integer,
            batchDelay: Integer
    ]

    private static final Map INSERT_PARAMS = [
            db: CharSequence,
            into: CharSequence,
            columns: [CharSequence, List],
            statement: CharSequence,
            batch: Integer, // deprecated
            batchSize: Integer,
            setup: CharSequence
    ]

    private Session session
    private SqlConfig config

    // built-in in-memory databases used in this run, closed on session shutdown
    private final Set<SqlDataSource> builtInDataSources = ConcurrentHashMap.<SqlDataSource>newKeySet()

    protected void init(Session session) {
        this.session = session
        this.config = new SqlConfig((Map) session.config.navigate('sql.db'))
        session.onShutdown { closeBuiltInDatabases() }
    }

    protected SqlDataSource track(SqlDataSource dataSource) {
        if( dataSource?.isBuiltIn() )
            builtInDataSources.add(dataSource)
        return dataSource
    }

    protected void closeBuiltInDatabases() {
        for( final dataSource : builtInDataSources ) {
            builtInDataSources.remove(dataSource)
            try( Connection conn = Sql.newInstance(dataSource.toMap()).getConnection(); Statement stm = conn.createStatement() ) {
                stm.execute('SHUTDOWN')
            }
            catch( Exception e ) {
                log.debug "Unable to shut down in-memory database: ${dataSource.url} - cause: ${e.message}"
            }
        }
    }

    @Description('''
        The `fromQuery` factory queries a SQL database and creates a channel that emits a tuple for each row in the result set.
    ''')
    @Factory
    DataflowWriteChannel fromQuery(String query) {
        fromQuery(Collections.emptyMap(), query)
    }

    @Description('''
        The `fromQuery` factory queries a SQL database and creates a channel that emits a tuple for each row in the result set.

        Options:
        - `db`: the database name, as defined under `sql.db` in the Nextflow configuration (default: `default`)
        - `batchSize`: query the data in batches of the given size (requires `LIMIT` and `OFFSET` support, and the query must not already contain `LIMIT` or `OFFSET`)
        - `batchDelay`: the delay in milliseconds between batches when using `batchSize` (default: `100`)
        - `emitColumns`: when `true`, emit the column names as the first tuple
    ''')
    @Factory
    DataflowWriteChannel fromQuery(Map opts, String query) {
        CheckHelper.checkParams('fromQuery', opts, QUERY_PARAMS)
        return queryToChannel(query, opts)
    }

    protected DataflowWriteChannel queryToChannel(String query, Map opts) {
        final channel = CH.create()
        final dataSource = dataSourceFromOpts(opts)
        final handler = new QueryHandler()
                .withDataSource(dataSource)
                .withStatement(query)
                .withTarget(channel)
                .withOpts(opts)
        session.addIgniter {-> handler.perform(true) }
        return channel
    }

    protected SqlDataSource dataSourceFromOpts(Map opts) {
        final dsName = (opts?.db ?: 'default') as String
        final dataSource = track(config.getDataSource(dsName))
        if( dataSource==null ) {
            def msg = "Unknown db name: $dsName"
            def choices = config.getDataSourceNames()
            if( choices )
                msg += " - Available databases: " + choices.join(', ')
            throw new IllegalArgumentException(msg)
        }
        return dataSource
    }

    @Description('''
        The `sqlInsert` operator inserts each value from a source channel into a SQL database table, and forwards the values unchanged.

        Options:
        - `db`: the database name, as defined under `sql.db` in the Nextflow configuration (default: `default`)
        - `into`: the target table
        - `columns`: the target column names, as a list or a comma-separated string, matching the tuple values emitted by the channel (default: inferred from the table columns for tuples, or from the keys for maps)
        - `statement`: the SQL `INSERT` statement to execute, using `?` as a placeholder for each value (overrides `into` and `columns`)
        - `batchSize`: insert the data in batches of the given size (default: `10`)
        - `setup`: a SQL statement to execute before inserting the data, e.g. to create the target table
    ''')
    @Operator
    DataflowWriteChannel sqlInsert( DataflowReadChannel source, Map opts=null ) {
        CheckHelper.checkParams('sqlInsert', opts, INSERT_PARAMS)
        final dataSource = dataSourceFromOpts(opts)
        final target = CH.createBy(source)
        final singleton = target instanceof DataflowExpression
        final insert = new InsertHandler(dataSource, opts)

        final next = { it ->
            insert.perform(it)
            target.bind(it)
        }

        final done = {
            insert.close()
            if( !singleton ) target.bind(Channel.STOP)
        }

        DataflowHelper.subscribeImpl(source, [onNext: next, onComplete: done])
        return target
    }

    private static final Map EXECUTE_PARAMS = [
            db: CharSequence,
            statement: CharSequence,
            params: Object,
            batch: Object
    ]

    // rows sent to the database per JDBC batch (all in one transaction, if supported)
    private static final int EXECUTE_BATCH_SIZE = 1000

    /**
     * Execute a SQL statement that does not return a result set (DDL/DML statements)
     * For DML statements (INSERT, UPDATE, DELETE), it returns a result map with success status and number of affected rows
     * For DDL statements (CREATE, ALTER, DROP), it returns a result map with success status
     *
     * @param opts A map containing 'db' (database alias) and 'statement' (SQL string to execute)
     * @return A map containing 'success' (boolean), 'result' (rows affected or null) and optionally 'error' (message)
     */
    @Description('''
        The `sqlExecute` function executes a SQL statement that does not return a result set, such as a DDL or DML statement. It returns a map with `success` and either `result` (the number of affected rows for DML statements, `null` for DDL statements) or `error` (on failure). Errors are returned instead of thrown.

        Options:
        - `db`: the database name, as defined under `sql.db` in the Nextflow configuration (default: `default`)
        - `statement`: the SQL statement to execute
        - `params`: the bind parameters for the statement, as a list for positional `?` placeholders or a map for named `:name` placeholders
        - `batch`: a list of bind parameters (each a list or a map, as for `params`), to execute the statement once for each in a single transaction (if the database driver supports transactions). The result is the total number of affected rows. Can't be used with `params`
    ''')
    @Function
    Map sqlExecute(Map opts) {
        CheckHelper.checkParams('sqlExecute', opts, EXECUTE_PARAMS)
        
        final String dbName = opts.db as String ?: 'default'
        final String statement = opts.statement as String
        
        if (!statement)
            return [success: false, error: "Missing required parameter 'statement'"]
        if (opts.params != null && opts.batch != null)
            return [success: false, error: "Options 'params' and 'batch' cannot be used together"]
        if (opts.params != null && !(opts.params instanceof List || opts.params instanceof Map))
            return [success: false, error: "Option 'params' must be a list or a map"]
        if (opts.batch != null && opts.batch !instanceof List)
            return [success: false, error: "Option 'batch' must be a list"]
            
        final sqlConfig = new SqlConfig((Map) session.config.navigate('sql.db'))
        final SqlDataSource dataSource = track(sqlConfig.getDataSource(dbName))
        
        if (dataSource == null) {
            def msg = "Unknown db name: $dbName"
            def choices = sqlConfig.getDataSourceNames().closest(dbName) ?: sqlConfig.getDataSourceNames()
            if (choices?.size() == 1)
                msg += " - Did you mean: ${choices.get(0)}?"
            else if (choices)
                msg += " - Did you mean any of these?\n" + choices.collect { "  $it" }.join('\n') + '\n'
            return [success: false, error: msg]
        }
        
        try (Sql sql = Sql.newInstance(dataSource.toMap())) {
            final normalizedStatement = statement.trim()
            final isDDL = normalizedStatement ==~ /(?is)(create|alter|drop|truncate)\b.*/
            final rowsAffected = opts.batch != null
                ? executeBatch(sql, normalizedStatement, opts.batch as List)
                : executeStatement(sql, normalizedStatement, opts.params, isDDL)
            return [success: true, result: isDDL ? null : rowsAffected]
        }
        catch (Exception e) {
            log.error("Error executing SQL statement: ${e.message}", e)
            return [success: false, error: e.message]
        }
    }

    private static Integer executeStatement(Sql sql, String statement, Object params, boolean isDDL) {
        final bound = params != null ? new SqlParams(sql, statement).bind(params) : null
        sql.enableNamedQueries = false
        if (isDDL) {
            bound != null ? sql.execute(bound.sql, bound.params) : sql.execute(statement)
            return null
        }
        return bound != null ? sql.executeUpdate(bound.sql, bound.params) : sql.executeUpdate(statement)
    }

    /**
     * Execute a statement for each set of bind parameters as a JDBC batch,
     * in a single transaction that is rolled back on error (if the driver
     * supports transactions).
     *
     * Returns the total number of affected rows. Rows for which the driver
     * doesn't report a count ({@link java.sql.Statement#SUCCESS_NO_INFO}) are not counted.
     */
    @PackageScope
    static int executeBatch(Sql sql, String statement, List batch) {
        if (!batch)
            return 0
        // bind every row up front, so that invalid rows fail before touching the database
        final params = new SqlParams(sql, statement)
        final bound = batch.collect { row -> params.bind(row) }
        final boundStatement = bound.first().sql
        if (bound.any { it.sql != boundStatement })
            throw new IllegalArgumentException("Batch rows must be either all lists or all maps")
        sql.enableNamedQueries = false
        final runBatch = { ->
            sql.withBatch(EXECUTE_BATCH_SIZE, boundStatement) { BatchingPreparedStatementWrapper ps ->
                for (final row : bound)
                    ps.addBatch(row.params)
            }
        }
        final conn = sql.getConnection()
        final savedAutoCommit = conn.getAutoCommit()
        int[] counts
        if (!beginTransaction(conn)) {
            log.debug "[SQL] The database driver does not support transactions -- the batch is not atomic"
            counts = runBatch()
        }
        else {
            try {
                counts = runBatch()
                conn.commit()
            }
            catch (Throwable e) {
                try { conn.rollback() } catch (SQLException re) { e.addSuppressed(re) }
                throw e
            }
            finally {
                try { conn.setAutoCommit(savedAutoCommit) } catch (SQLException ignored) { }
            }
        }
        int total = 0
        for (final count : counts)
            if (count > 0) total += count
        return total
    }

    /**
     * Disable autocommit to start a transaction, or return false if
     * the driver doesn't support transactions (e.g. Databricks).
     */
    private static boolean beginTransaction(Connection conn) {
        try {
            if (!conn.getMetaData().supportsTransactions())
                return false
            conn.setAutoCommit(false)
            return true
        }
        catch (SQLFeatureNotSupportedException e) {
            return false
        }
    }
}
