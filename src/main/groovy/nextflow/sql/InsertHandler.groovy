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
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.Callable

import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.transform.Immutable
import groovy.transform.Memoized
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import groovy.util.logging.Slf4j
import nextflow.sql.config.SqlDataSource
import nextflow.util.TupleHelper
/**
 * Handle SQL insert operation
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class InsertHandler implements Closeable {

    static final List<String> QM = ['?']
    static final int DEFAULT_BATCH_SIZE = 10

    private Map opts
    private SqlDataSource ds
    private Connection connection
    private String sqlStatement
    private String into
    private List<String> columns
    private List<String> recordKeys
    private int batchSize
    private int batchCount
    private PreparedStatement preparedStatement
    private String setupStatement

    InsertHandler(SqlDataSource ds, Map opts) {
        this.ds = ds
        this.opts = opts ?: Collections.emptyMap()
        this.into = this.opts.into
        this.columns = cols0(this.opts.columns)
        this.sqlStatement = this.opts.statement
        this.batchSize = this.opts.batch ? this.opts.batch as int : DEFAULT_BATCH_SIZE
        this.batchSize = this.opts.batchSize ? this.opts.batchSize as int : DEFAULT_BATCH_SIZE
        this.setupStatement = this.opts.setup
        if( this.opts.batch )
            log.warn "The option 'batch' for the 'sqlInsert' operator has been deprecated - Use 'batchSize' instead"
        if( batchSize<1 )
            throw new IllegalArgumentException("SQL batch option must be greater than zero: $batchSize")
    }

    private void safeSetAutoCommit(Connection connection, boolean value) {
        try {
            connection.setAutoCommit(value)
        }
        catch(SQLFeatureNotSupportedException e) {
            log.debug "setAutoCommit is not supported by this driver (likely Databricks), continuing: ${e.message}"
        }
    }

    private void safeCommit(Connection connection) {
        try {
            connection.commit()
        }
        catch(SQLFeatureNotSupportedException e) {
            log.debug "commit is not supported by this driver (likely Databricks), continuing: ${e.message}"
        }
    }

    private void safeRollback(Connection connection) {
        try {
            if( !connection.getAutoCommit() )
                connection.rollback()
        }
        catch(SQLException e) {
            log.debug "[SQL] unable to end the read transaction, continuing: ${e.message}"
        }
    }

    private Connection getConnection() {
        if( connection == null ) {
            connection = Sql.newInstance(ds.toMap()).getConnection()
            checkCreate(connection)
            safeSetAutoCommit(connection, false)
        }
        return connection
    }

    private void checkCreate(Connection connection) {
        if( !setupStatement )
            return
        // this allows performing a SQL statement ideally create the required table
        // note: the underlying DB should support the idiom `create table if not exist`
        try( def stm = connection.createStatement()) {
            stm.executeUpdate(setupStatement)
        }
    }

    protected List cols0(names) {
        if( !names )
            return null
        if( names instanceof List )
            return names
        if( names instanceof CharSequence )
            return names.tokenize(',').collect(it -> it.trim())
        throw new IllegalArgumentException("Invalid sqlInsert 'columns' option - Offending value: $names [${names.getClass().getName()}]")
    }

    protected String makeStatement(Object entry) {
        if( sqlStatement )
            return sqlStatement
        if( !into )
            throw new IllegalArgumentException("Missing sqlInsert target table - Use 'into' option to specify it")

        if( !columns )
            columns = fetchColNames(entry)
        if( !columns )
            throw new IllegalArgumentException("Missing sqlInsert 'columns' option")

        final cnt = columns.size()
        final result = "insert into $into ( ${columns.join(',')} ) values ( ${(QM * cnt).join(',')} )"
        return result
    }

    protected List<String> fetchColNames(Object entry) {
        if( entry instanceof List ) {
            return fetchColNames0(into)
        }
        else if( entry instanceof Map ) {
            return fetchColNames1(into, (Map)entry)
        }
        else
            throw new IllegalStateException("Invalid sqlInsert entry data type: $entry [${entry?.getClass()?.getName()}]")
    }

    protected String getSqlStatement(Object entry) {
        if( sqlStatement )
            return sqlStatement
        sqlStatement = makeStatement(entry)
        // end the read transaction from inferring the columns, otherwise SQLite fails with SQLITE_BUSY on the first write
        if( connection != null )
            safeRollback(connection)
        log.debug "[SQL] sqlInsert statement: \"${sqlStatement}\""
        return sqlStatement
    }

    void perform(Object entry) {
        // make sure is not null
        if( entry==null )
            throw new IllegalArgumentException("Invalid sqlInsert entry - It must be a non-null value")

        if( entry instanceof Map ) {
            final sql = getSqlStatement(entry)
            performAsMap(sql, entry)
        }
        else if( entry instanceof List ) {
            final sql = getSqlStatement(entry)
            performAsTuple(sql, entry)
        }
        else {
            entry = TupleHelper.listOf(entry)
            final sql = getSqlStatement(entry)
            performAsTuple(sql, entry)
        }

    }

    protected void performAsMap(String sql, Map record) {
        executeStm(sql) {stm ->
            if( recordKeys==null )
                recordKeys = columns.collect(it -> findKey(record, it))
            // loop over the expected columns and fetch a corresponding record value to be inserted
            for(int i=0; i<columns.size(); i++ ) {
                final value = record.get(recordKeys[i])
                stm.setObject(i+1, value)
            }
            // report a debug line
            log.debug "[SQL] perform sql statemet=$sql; entry=$record"
        }
    }

    protected void performAsTuple(String sql, List tuple) {
        executeStm(sql) { stm ->
            // loop over the tuple values and set a corresponding sql statement value
            for(int i=0; i<tuple.size(); i++ ) {
                def value = tuple[i]
                stm.setObject(i+1, value)
            }
            // report a debug line
            log.debug "[SQL] perform sql statemet=$sql; entry=$tuple"
        }
    }

    protected void executeStm(String sql, @ClosureParams(value = SimpleType.class, options = "java.sql.PreparedStatement") Closure operation) {
        // create the statement if needed
        if( preparedStatement==null )
            preparedStatement = getConnection().prepareStatement(sql)

        // make sure to clear previous params
        preparedStatement.clearParameters()
        // invoke the operation closure with setup the statement params
        operation.call(preparedStatement)
        // add to current batch
        preparedStatement.addBatch()
        // if the batch size is reached, perform the batch statement
        if( ++batchCount == batchSize ) {
            try {
                preparedStatement.executeBatch()
                preparedStatement.clearBatch()
            }
            finally {
                // reset the current batch count
                batchCount = 0
                // make sure to commit the current batch
                safeCommit(connection)
            }
        }
    }

    @Memoized
    protected List<String> fetchColNames0(String tableName) {
        final conn = getConnection()
        final metaData = conn.getMetaData()
        final names = TableName.parse(tableName, metaData)
        final candidates = names.collectMany(it -> tableNameCandidates(metaData, it))
        // look up an unqualified table in the current catalog and schema first,
        // then anywhere (e.g. a table in the Postgres search path) as long as it is unique
        final lookups = new LinkedHashSet<TableName>()
        if( names.size()==1 && !names[0].qualified ) {
            final catalog = tryMetadata('current catalog', () -> conn.getCatalog())
            final schema = tryMetadata('current schema', () -> conn.getSchema())
            for( TableName it : candidates )
                lookups.add(new TableName(catalog, schema, it.table))
        }
        lookups.addAll(candidates)
        for( TableName it : lookups ) {
            final result = queryColNames(metaData, it)
            if( result != null )
                return result
        }
        return []
    }

    /**
     * Get the columns of the given table, or null if the table is not found.
     */
    private List<String> queryColNames(DatabaseMetaData metaData, TableName name) {
        final escape = tryMetadata('search string escape', () -> metaData.getSearchStringEscape())
        final tables = new LinkedHashMap<List<String>,List<String>>()
        try( final resultSet = metaData.getColumns(name.catalog, escapePattern(name.schema, escape), escapePattern(name.table, escape), null) ) {
            while( resultSet.next() ) {
                final key = [resultSet.getString("TABLE_CAT"), resultSet.getString("TABLE_SCHEM"), resultSet.getString("TABLE_NAME")]
                // drop wildcard matches in case the driver doesn't honour the escape (ignoring case as SQLite does)
                if( !name.table.equalsIgnoreCase(key[2]) || !matchesPart(name.catalog, key[0]) || !matchesPart(name.schema, key[1]) )
                    continue
                tables.computeIfAbsent(key, k -> new ArrayList<String>()).add( resultSet.getString("COLUMN_NAME") )
            }
        }
        if( tables.size()>1 )
            throw new IllegalArgumentException("Ambiguous sqlInsert table '$into' - It matches ${tables.keySet().collect(it -> it.findAll().join('.')).join(', ')} - Use a qualified table name or the 'columns' option")
        return tables ? tables.values().first() : null
    }

    private static boolean matchesPart(String requested, String actual) {
        requested == null || actual == null || requested.equalsIgnoreCase(actual)
    }

    /**
     * Escape the LIKE wildcards in a metadata search pattern, so that it matches the name exactly.
     */
    protected static String escapePattern(String name, String escape) {
        if( name == null || !escape )
            return name
        return name
            .replace(escape, escape + escape)
            .replace('_', escape + '_')
            .replace('%', escape + '%')
    }

    private static <T> T tryMetadata(String description, Callable<T> action) {
        try {
            return action.call()
        }
        catch( SQLException | AbstractMethodError | UnsupportedOperationException e ) {
            log.debug "[SQL] unable to determine the ${description}: ${e.message}"
            return null
        }
    }

    /**
     * Get the table names to look up in the database metadata, i.e. the name
     * folded to the identifier case used by the database (since unquoted
     * identifiers are case-insensitive), followed by the name as given.
     */
    private Collection<TableName> tableNameCandidates(DatabaseMetaData metaData, TableName name) {
        final folded = new TableName(
            name.catalog != null ? foldCase(metaData, name.catalog) : null,
            name.schema != null ? foldCase(metaData, name.schema) : null,
            foldCase(metaData, name.table) )
        final result = new LinkedHashSet<TableName>()
        result.add(folded)
        result.add(name)
        return result
    }

    /**
     * A table name optionally qualified with a catalog and/or schema.
     */
    @Immutable
    static class TableName {
        String catalog
        String schema
        String table

        boolean isQualified() {
            catalog != null || schema != null
        }

        /**
         * Parse a name of the form {@code [[catalog.]schema.]table} into the possible
         * interpretations to look up. A single qualifier is a schema, or a catalog if the
         * database supports catalogs (e.g. MySQL databases, DuckDB attached databases).
         * An invalid name has no interpretations.
         */
        static List<TableName> parse(String name, DatabaseMetaData metaData) {
            final parts = name.split('\\.', -1) as List<String>
            if( parts.size()>3 || parts.any(it -> it.isEmpty()) )
                return []
            if( parts.size()==3 )
                return [new TableName(parts[0], parts[1], parts[2])]
            if( parts.size()==1 )
                return [new TableName(null, null, name)]
            final result = new ArrayList<TableName>()
            if( tryMetadata('schema support', () -> metaData.supportsSchemasInDataManipulation()) != Boolean.FALSE )
                result.add(new TableName(null, parts[0], parts[1]))
            if( tryMetadata('catalog support', () -> metaData.supportsCatalogsInDataManipulation()) )
                result.add(new TableName(parts[0], null, parts[1]))
            return result ?: [new TableName(null, parts[0], parts[1])]
        }
    }

    private static String foldCase(DatabaseMetaData metaData, String name) {
        try {
            if( metaData.storesUpperCaseIdentifiers() )
                return name.toUpperCase()
            if( metaData.storesLowerCaseIdentifiers() )
                return name.toLowerCase()
        }
        catch( SQLException | UnsupportedOperationException e ) {
            log.debug "[SQL] unable to determine identifier case for this database: ${e.message}"
        }
        return name
    }

    protected List<String> fetchColNames1(String tableName, Map<String,Object> record) {
        final keys = record.keySet()
        final duplicates = keys.groupBy(it -> it.toLowerCase()).values().findAll(it -> it.size()>1)
        if( duplicates )
            throw new IllegalArgumentException("Invalid sqlInsert record - Keys must be unique ignoring case: ${duplicates.collect(it -> it.join(',')).join('; ')} - Offending record: $record")
        final allCols = fetchColNames0(tableName)
        final metaData = getConnection().getMetaData()
        // a column matches a key ignoring case only if it was created unquoted
        // (i.e. it is stored in the database identifier case), otherwise it must match exactly
        final matches = { String col, String key -> col == key || (col.equalsIgnoreCase(key) && foldCase(metaData, col) == col) }
        final result = allCols.findAll(col -> keys.any(key -> matches(col, key)))
        final diff = keys.findAll(key -> !result.any(col -> matches(col, key)))
        if( diff )
            throw new IllegalArgumentException("Unknown columns for table '$into': ${diff.join(',')} - Offending record: $record")
        return result
    }

    private static String findKey(Map record, String column) {
        if( record.containsKey(column) )
            return column
        final matches = record.keySet().findAll(it -> column.equalsIgnoreCase(it as String))
        if( matches.size()>1 )
            throw new IllegalArgumentException("Invalid sqlInsert record - Ambiguous keys for column '$column': ${matches.join(',')} - Offending record: $record")
        return matches ? matches.first() as String : column
    }

    void close() {
        try {
            if( preparedStatement && batchCount>0 ) {
                log.debug("[SQL] flushing and committing open batch")
                preparedStatement.executeBatch()
                batchCount = 0
                preparedStatement.close()
                safeCommit(connection)
            }
        }
        finally {
            if( connection ) {
                log.debug "[SQL] closing JDBC connection"
                connection.close()
                connection = null
            }
        }
    }
}
