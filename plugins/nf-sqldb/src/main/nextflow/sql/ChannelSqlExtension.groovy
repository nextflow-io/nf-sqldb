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


import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.DataflowWriteChannel
import groovyx.gpars.dataflow.expression.DataflowExpression
import nextflow.Channel
import nextflow.Global
import nextflow.NF
import nextflow.Session
import nextflow.extension.CH
import nextflow.extension.DataflowHelper
import nextflow.plugin.extension.Factory
import nextflow.plugin.extension.Function
import nextflow.plugin.extension.Operator
import nextflow.plugin.extension.PluginExtensionPoint
import nextflow.sql.config.SqlConfig
import nextflow.sql.config.SqlDataSource
import nextflow.util.CheckHelper
import java.sql.Connection
import java.sql.Statement
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

    /**
     * Factory used to create the {@link QueryOp} instance backing the {@code fromQuery}
     * channel factory. Defaults to {@link QueryHandler}. A downstream plugin can supply
     * its own implementation via {@link #registerQueryOpProvider(groovy.lang.Closure)}.
     * <p>
     * Volatile because registration typically happens on the plugin-start thread while
     * {@link #createQueryOp()} runs on DSL/operator threads; without this, readers are not
     * guaranteed to ever observe a published provider.
     */
    private static volatile Closure<QueryOp> queryOpProvider

    /**
     * Register a custom {@link QueryOp} provider used by {@code fromQuery} in place of the
     * default {@link QueryHandler}. Pass {@code null} to restore the default, or call
     * {@link #unregisterQueryOpProvider(groovy.lang.Closure)} instead for a clearer call site.
     * <p>
     * If a different, non-null provider is already registered, it is overwritten and a
     * warning is logged, since this usually indicates two plugins competing for the same
     * hook.
     * <p>
     * <b>Prerequisites and limitations for callers:</b>
     * <ul>
     *     <li>The registering plugin must declare an actual dependency on {@code nf-sqldb}
     *         so it shares this exact {@code ChannelSqlExtension} class. A shaded/bundled
     *         copy of this class loaded by the plugin will register against its own copy
     *         of this static field, and {@code fromQuery} will silently keep using the
     *         default {@link QueryHandler}.</li>
     *     <li>This static field pins a reference to the closure, and transitively to the
     *         classloader of the plugin that registered it. A plugin that is stopped or
     *         unloaded must call {@link #unregisterQueryOpProvider(groovy.lang.Closure)} (or
     *         {@code null}); otherwise its classloader leaks and {@code fromQuery} keeps
     *         dispatching into a provider backed by a dead plugin.</li>
     *     <li>The {@code provider} closure is invoked with no arguments and has no access
     *         to the current {@code session}, {@code opts}, or resolved {@code SqlDataSource}
     *         — a provider that needs any of that state must capture it itself at
     *         registration time.</li>
     * </ul>
     */
    static synchronized void registerQueryOpProvider(Closure<QueryOp> provider) {
        final current = queryOpProvider
        if( current!=null && provider!=null && current!=provider )
            log.warn("Overwriting an existing QueryOp provider - this usually means two plugins are registering competing QueryOp providers")
        queryOpProvider = provider
    }

    /**
     * Remove a previously registered {@link QueryOp} provider, restoring the default
     * {@link QueryHandler} behavior, but only if {@code provider} is still the currently
     * registered one (identity match). A plugin that registered a provider should call
     * this with the same closure when it is stopped or unloaded, to avoid pinning its
     * classloader.
     * <p>
     * If a different provider is currently registered (e.g. another plugin has since
     * overwritten it), the current registration is left untouched and a warning is
     * logged, since clearing it would silently disable that other plugin's hook.
     */
    static synchronized void unregisterQueryOpProvider(Closure<QueryOp> provider) {
        final current = queryOpProvider
        if( current==null )
            return
        if( current!=provider ) {
            log.warn("Ignoring unregisterQueryOpProvider call - the currently registered QueryOp provider does not match the one being unregistered")
            return
        }
        queryOpProvider = null
    }

    protected QueryOp createQueryOp() {
        final provider = queryOpProvider
        if( !provider )
            return new QueryHandler()
        final result = provider.call()
        if( result==null )
            throw new IllegalStateException("QueryOp provider returned a null instance")
        return result
    }

    private Session session
    private SqlConfig config

    protected void init(Session session) {
        this.session = session
        this.config = new SqlConfig((Map) session.config.navigate('sql.db'))
    }

    @Factory
    DataflowWriteChannel fromQuery(String query) {
        fromQuery(Collections.emptyMap(), query)
    }

    @Factory
    DataflowWriteChannel fromQuery(Map opts, String query) {
        CheckHelper.checkParams('fromQuery', opts, QUERY_PARAMS)
        return queryToChannel(query, opts)
    }

    protected DataflowWriteChannel queryToChannel(String query, Map opts) {
        final channel = CH.create()
        final dataSource = dataSourceFromOpts(opts)
        final handler = createQueryOp()
                .withDataSource(dataSource)
                .withStatement(query)
                .withTarget(channel)
                .withOpts(opts)
        if(NF.dsl2) {
            session.addIgniter {-> handler.perform(true) }
        }
        else {
            handler.perform(true)
        }
        return channel
    }

    protected SqlDataSource dataSourceFromOpts(Map opts) {
        final dsName = (opts?.db ?: 'default') as String
        final dataSource = config.getDataSource(dsName)
        if( dataSource==null ) {
            def msg = "Unknown db name: $dsName"
            def choices = config.getDataSourceNames()
            if( choices )
                msg += " - Available databases: " + choices.join(', ')
            throw new IllegalArgumentException(msg)
        }
        return dataSource
    }

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
            statement: CharSequence
    ]

    /**
     * Execute a SQL statement that does not return a result set (DDL/DML statements)
     * For DML statements (INSERT, UPDATE, DELETE), it returns a result map with success status and number of affected rows
     * For DDL statements (CREATE, ALTER, DROP), it returns a result map with success status
     *
     * @param params A map containing 'db' (database alias) and 'statement' (SQL string to execute)
     * @return A map containing 'success' (boolean), 'result' (rows affected or null) and optionally 'error' (message)
     */
    @Function
    Map sqlExecute(Map params) {
        CheckHelper.checkParams('sqlExecute', params, EXECUTE_PARAMS)
        
        final String dbName = params.db as String ?: 'default'
        final String statement = params.statement as String
        
        if (!statement)
            return [success: false, error: "Missing required parameter 'statement'"]
            
        final sqlConfig = new SqlConfig((Map) session.config.navigate('sql.db'))
        final SqlDataSource dataSource = sqlConfig.getDataSource(dbName)
        
        if (dataSource == null) {
            def msg = "Unknown db name: $dbName"
            def choices = sqlConfig.getDataSourceNames().closest(dbName) ?: sqlConfig.getDataSourceNames()
            if (choices?.size() == 1)
                msg += " - Did you mean: ${choices.get(0)}?"
            else if (choices)
                msg += " - Did you mean any of these?\n" + choices.collect { "  $it" }.join('\n') + '\n'
            return [success: false, error: msg]
        }
        
        try (Connection conn = groovy.sql.Sql.newInstance(dataSource.toMap()).getConnection()) {
            try (Statement stm = conn.createStatement()) {
                String normalizedStatement = normalizeStatement(statement)
                
                boolean isDDL = normalizedStatement.trim().toLowerCase().matches("^(create|alter|drop|truncate).*")
                
                if (isDDL) {
                    stm.execute(normalizedStatement)
                    return [success: true, result: null]
                } else {
                    Integer rowsAffected = stm.executeUpdate(normalizedStatement)
                    return [success: true, result: rowsAffected]
                }
            }
        }
        catch (Exception e) {
            log.error("Error executing SQL statement: ${e.message}", e)
            return [success: false, error: e.message]
        }
    }

    /**
     * Normalizes a SQL statement by adding a semicolon if needed
     *
     * @param statement The SQL statement to normalize
     * @return The normalized SQL statement
     */
    private static String normalizeStatement(String statement) {
        if (!statement)
            return null
        def result = statement.trim()
        if (!result.endsWith(';'))
            result += ';'
        return result
    }
}
