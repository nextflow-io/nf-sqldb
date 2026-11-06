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


import java.sql.Blob
import java.sql.Clob
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLXML

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
import nextflow.script.types.Record
import nextflow.sql.config.SqlConfig
import nextflow.sql.config.SqlDataSource
import nextflow.util.CheckHelper
import nextflow.util.RecordMap
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import groovy.sql.Sql
/**
 * Provide a channel factory extension that allows the execution of Sql queries
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class ChannelSqlExtension extends PluginExtensionPoint {

    // groovy-sql logs failed statements as warnings to stderr, which duplicates the errors reported by
    // sqlExecute and sqlQuery; keep a reference, since java.util.logging only holds loggers weakly
    private static final Logger GROOVY_SQL_LOGGER = Logger.getLogger(Sql.name)

    static {
        GROOVY_SQL_LOGGER.setLevel(Level.SEVERE)
    }

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

    private static String dbNameFromOpts(Map opts) {
        return (opts?.db ?: 'default') as String
    }

    protected SqlDataSource dataSourceFromOpts(Map opts) {
        final dsName = dbNameFromOpts(opts)
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
        if (opts.batch != null && opts.batch !instanceof Collection)
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
                ? executeBatch(sql, normalizedStatement, toList(opts.batch as Collection))
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

    // copy by iterating, since some collections don't support toArray (e.g. the bag emitted by collect)
    private static List toList(Collection items) {
        final result = new ArrayList(items.size())
        for( final item : items )
            result.add(item)
        return result
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

    private static final Map SQL_QUERY_PARAMS = [
            db: CharSequence,
            query: CharSequence,
            params: [List, Map]
    ]

    @Description('''
        The `sqlQuery` function queries a SQL database and returns a list of records, one for each row in the result set. Each record maps the column labels to the row values, in column order. If the database stores unquoted identifiers in upper case (e.g. H2, Oracle), upper-case labels are converted to lower case, so that `SELECT id ...` gives `row.id` on any database.

        The whole result set is read into memory. Errors are thrown.

        Options:
        - `db`: the database name, as defined under `sql.db` in the Nextflow configuration (default: `default`)
        - `query`: the SQL query to execute
        - `params`: the bind parameters for the query, as a list for positional `?` placeholders or a map for named `:name` placeholders
    ''')
    @Function
    List<Record> sqlQuery(Map opts) {
        CheckHelper.checkParams('sqlQuery', opts, SQL_QUERY_PARAMS)
        final query = (opts?.query as String)?.trim()
        if( !query )
            throw new IllegalArgumentException("Missing required option 'query' for sqlQuery")
        final dbName = dbNameFromOpts(opts)
        final dataSource = dataSourceFromOpts(opts)
        try( Sql sql = Sql.newInstance(dataSource.toMap()) ) {
            return executeQuery(sql, query, opts.params)
        }
        catch( IllegalArgumentException e ) {
            throw e
        }
        catch( SQLException | RuntimeException e ) {
            throw new IllegalStateException("sqlQuery failed on db '${dbName}': ${e.message}", e)
        }
    }

    private static List<Record> executeQuery(Sql sql, String query, Object params) {
        final bound = params != null ? new SqlParams(sql, query).bind(params) : null
        sql.enableNamedQueries = false
        final List<Record> result = []
        final reader = { ResultSet rs -> result.addAll(readRows(rs)) }
        if( bound != null )
            sql.query(bound.sql, bound.params, reader)
        else
            sql.query(query, reader)
        return result
    }

    private static List<Record> readRows(ResultSet rs) {
        final meta = rs.getMetaData()
        final upperCase = storesUpperCaseIdentifiers(rs.getStatement().getConnection().getMetaData())
        final count = meta.getColumnCount()
        final keys = new ArrayList<String>(count)
        for( int i=1; i<=count; i++ )
            keys << columnKey(meta.getColumnLabel(i), upperCase)
        final duplicates = keys.findAll { k -> keys.count(k) > 1 }.unique()
        if( duplicates )
            throw new IllegalArgumentException("Query result has duplicate column labels: ${duplicates.join(', ')} - use column aliases to make them unique")
        final List<Record> rows = []
        while( rs.next() ) {
            final values = new LinkedHashMap<String,Object>(count)
            for( int i=1; i<=count; i++ )
                values.put(keys[i-1], materialize(rs.getObject(i)))
            rows << new RecordMap(values)
        }
        return rows
    }

    /**
     * Convert values that are only valid while the connection is open
     * (large objects and arrays) into plain values, and arrays into lists.
     */
    private static Object materialize(Object value) {
        if( value instanceof Clob ) {
            final clob = (Clob) value
            try { return clob.getSubString(1, (int) clob.length()) }
            finally { free { clob.free() } }
        }
        if( value instanceof Blob ) {
            final blob = (Blob) value
            try { return blob.getBytes(1, (int) blob.length()) }
            finally { free { blob.free() } }
        }
        if( value instanceof java.sql.Array ) {
            final array = (java.sql.Array) value
            try { return arrayToList(array.getArray()) }
            finally { free { array.free() } }
        }
        if( value instanceof SQLXML ) {
            final xml = (SQLXML) value
            try { return xml.getString() }
            finally { free { xml.free() } }
        }
        // some drivers (e.g. H2 1.x) return arrays as plain Java arrays
        if( value != null && value.getClass().isArray() && value !instanceof byte[] )
            return arrayToList(value)
        return value
    }

    private static List arrayToList(Object array) {
        return (array as List).collect { materialize(it) }
    }

    private static void free(Closure action) {
        try {
            action.call()
        }
        catch( SQLFeatureNotSupportedException | AbstractMethodError e ) {
            // not supported by the driver
        }
    }

    /**
     * Get the record key for a column label: an upper-case label is
     * converted to lower case if the database stores unquoted identifiers
     * in upper case, so that unquoted columns have the same key on any
     * database, while quoted mixed-case labels are preserved.
     */
    protected static String columnKey(String label, boolean upperCaseIdentifiers) {
        if( upperCaseIdentifiers && label == label.toUpperCase(Locale.ROOT) )
            return label.toLowerCase(Locale.ROOT)
        return label
    }

    private static boolean storesUpperCaseIdentifiers(DatabaseMetaData metaData) {
        try {
            return metaData.storesUpperCaseIdentifiers()
        }
        catch( SQLException | UnsupportedOperationException e ) {
            log.debug "[SQL] unable to determine identifier case for this database: ${e.message}"
            return false
        }
    }
}
