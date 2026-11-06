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

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import groovy.sql.Sql
import nextflow.Global
import nextflow.Session
import nextflow.sql.config.SqlDataSource
import org.h2.tools.Server
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Tests for the connection pools used by the SQL functions
 */
@Timeout(30)
class ConnectionPoolTest extends Specification {

    private Sql sql

    private ChannelSqlExtension ext

    private List<Driver> drivers = []

    def setup() {
        Global.session = Mock(Session)
    }

    def cleanup() {
        ext?.close()
        sql?.close()
        drivers.each { DriverManager.deregisterDriver(it) }
        Global.session = null
    }

    private String createDatabase(String table='t') {
        final url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        sql = Sql.newInstance(url, 'sa', null)
        sql.execute("CREATE TABLE $table(id INT PRIMARY KEY, name VARCHAR(255))".toString())
        return url
    }

    private ChannelSqlExtension extension(Map db) {
        final session = Mock(Session) {
            getConfig() >> [sql: [db: [test: db]]]
        }
        ext = new ChannelSqlExtension()
        ext.init(session)
        return ext
    }

    private Object sessionId() {
        ext.sqlQuery(db: 'test', query: 'SELECT SESSION_ID() AS id').first().id
    }

    private int count(String table='t') {
        sql.firstRow("SELECT COUNT(*) AS n FROM $table".toString()).n as int
    }

    /**
     * Register a JDBC driver for URLs with the given prefix, which opens
     * connections with the given closure (called with the rest of the URL).
     */
    private void registerDriver(String prefix, Closure<Connection> factory) {
        Driver driver = Stub(Driver) {
            acceptsURL(_) >> { String url -> url.startsWith(prefix) }
            connect(_, _) >> { String url, Properties info -> url.startsWith(prefix) ? factory.call(url.substring(prefix.length())) : null }
        }
        DriverManager.registerDriver(driver)
        drivers << driver
    }

    /**
     * Wrap a connection so that the given methods throw
     * {@link SQLFeatureNotSupportedException}, as with drivers that don't
     * support them (e.g. Databricks).
     */
    private static Connection unsupported(Connection conn, Collection<String> methods) {
        return (Connection) Proxy.newProxyInstance(Connection.classLoader, [Connection] as Class[], { proxy, Method m, Object[] args ->
            if( m.name in methods )
                throw new SQLFeatureNotSupportedException("Not supported: ${m.name}", '0A000')
            try { return m.invoke(conn, args) }
            catch( InvocationTargetException e ) { throw e.cause }
        } as InvocationHandler)
    }

    def 'should reuse connections'() {
        given:
        extension(url: createDatabase())

        when:
        def ids = (1..10).collect { sessionId() }
        (1..10).each { i -> assert ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (?, ?)', params: [i, 'x']).success }
        ids += (1..10).collect { sessionId() }
        then:
        ids.unique().size() == 1
        count() == 10
    }

    def 'should bound the number of connections for concurrent calls'() {
        given:
        extension(url: createDatabase(), maxPoolSize: 3)
        def executor = Executors.newFixedThreadPool(8)

        when:
        def futures = (1..40).collect { int i ->
            executor.submit({ ->
                final result = ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (?, ?)', params: [i, "n$i".toString()])
                assert result.success, result.error
                ext.sqlQuery(db: 'test', query: 'SELECT SESSION_ID() AS sid, name FROM t WHERE id = ?', params: [i]).first()
            } as Callable)
        }
        def rows = futures.collect { it.get(20, TimeUnit.SECONDS) }
        then:
        rows*.name == (1..40).collect { "n$it".toString() }
        rows*.sid.unique().size() <= 3
        count() == 40
        and:
        ext.pools.getPool(ext.dataSourceFromOpts(db: 'test')).hikariPoolMXBean.totalConnections <= 3

        cleanup:
        executor?.shutdownNow()
    }

    def 'should use the default pool size'() {
        given:
        extension(url: createDatabase())

        when:
        sessionId()
        then:
        ext.pools.getPool(ext.dataSourceFromOpts(db: 'test')).maximumPoolSize == ConnectionPools.DEFAULT_MAX_POOL_SIZE
    }

    def 'should restore the connection state after a batch'() {
        given:
        extension(url: createDatabase(), maxPoolSize: 1)
        def ds = ext.dataSourceFromOpts(db: 'test')
        def id = sessionId()

        when: 'a batch succeeds'
        def result = ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (?, ?)', batch: [[1, 'a'], [2, 'b']])
        then:
        result == [success: true, result: 2]
        ext.pools.withConnection(ds, 'test') { Connection conn -> conn.autoCommit }
        count() == 2
        sessionId() == id

        when: 'a batch fails'
        result = ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (?, ?)', batch: [[3, 'c'], [1, 'dup']])
        then:
        !result.success
        ext.pools.withConnection(ds, 'test') { Connection conn -> conn.autoCommit }
        count() == 2

        and: 'the next statement is committed immediately'
        ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (?, ?)', params: [3, 'c']).success
        count() == 3
    }

    def 'should evict a connection that is left in a transaction'() {
        given:
        extension(url: createDatabase(), maxPoolSize: 1)
        def ds = ext.dataSourceFromOpts(db: 'test')
        def id = sessionId()

        when: 'autocommit is disabled with SQL'
        def disabled = ext.sqlExecute(db: 'test', statement: 'SET AUTOCOMMIT FALSE')
        def inserted = ext.sqlExecute(db: 'test', statement: "INSERT INTO t VALUES (1, 'a')")
        then: 'the next call gets a new connection, so the insert is committed'
        disabled.success
        inserted.success
        count() == 1
        sessionId() != id

        when: 'autocommit is disabled with JDBC'
        id = sessionId()
        ext.pools.withConnection(ds, 'test') { Connection conn ->
            conn.autoCommit = false
            conn.createStatement().withCloseable { it.executeUpdate("INSERT INTO t VALUES (2, 'b')") }
        }
        then: 'the transaction is rolled back'
        count() == 1
        sessionId() != id
    }

    def 'should evict the connection after a failed call'() {
        given:
        extension(url: createDatabase(), maxPoolSize: 1)
        def id = sessionId()

        when:
        def result = ext.sqlExecute(db: 'test', statement: 'INSERT INTO missing VALUES (1)')
        then:
        !result.success
        sessionId() != id
    }

    def 'should evict a connection that is closed by the database'() {
        given:
        def url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        extension(url: url, maxPoolSize: 1)

        when:
        ext.sqlExecute(db: 'test', statement: 'CREATE TABLE t(id INT)')
        def shutdown = ext.sqlExecute(db: 'test', statement: 'SHUTDOWN')
        then:
        shutdown.success
        and: 'the next call gets a new connection (to a new empty database)'
        ext.sqlQuery(db: 'test', query: 'SELECT 1 AS x') == [[x: 1]]
        ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (1)').error.contains('not found')
    }

    def 'should time out when all connections are in use'() {
        given:
        def url = createDatabase()
        def ds = new SqlDataSource(url: url, maxPoolSize: 1)
        def pools = new ConnectionPools(300)
        def borrowed = new CountDownLatch(1)
        def done = new CountDownLatch(1)
        def holder = Thread.start {
            pools.withConnection(ds, 'test') { Connection conn -> borrowed.countDown(); done.await() }
        }
        borrowed.await()

        when:
        pools.withConnection(ds, 'test') { Connection conn -> 1 }
        then:
        def e = thrown(SQLTransientConnectionException)
        e.message == "Timed out after 300ms waiting for a connection to database 'test' - all 1 connections are in use (see the `sql.db.<name>.maxPoolSize` config option)"

        when: 'the connection is returned'
        done.countDown()
        holder.join()
        then:
        pools.withConnection(ds, 'test') { Connection conn -> 1 } == 1

        cleanup:
        done.countDown()
        pools.close()
    }

    def 'should not pool connections when maxPoolSize is 0'() {
        given:
        extension(url: createDatabase(), maxPoolSize: 0)
        def ds = ext.dataSourceFromOpts(db: 'test')

        when:
        def ids = (1..3).collect { sessionId() }
        def results = (1..3).collect { i -> ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (?, ?)', params: [i, 'x']) }
        def batch = ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (?, ?)', batch: [[4, 'a'], [5, 'b']])
        then:
        ids.unique().size() == 3
        results.every { it.success }
        batch == [success: true, result: 2]
        ext.sqlQuery(db: 'test', query: 'SELECT COUNT(*) AS n FROM t') == [[n: 5]]
        and:
        ext.pools.getPool(ds) == null
    }

    def 'should classify database urls'() {
        expect:
        ConnectionPools.isEmbeddedFile(url) == embedded
        ConnectionPools.isMemory(url) == memory
        ConnectionPools.isPrivateMemory(url) == isPrivate

        where:
        url                                             | embedded | memory | isPrivate
        'jdbc:duckdb:/data/x.duckdb'                    | true     | false  | false
        'jdbc:duckdb:x.db'                              | true     | false  | false
        'jdbc:duckdb:'                                  | false    | true   | true
        'jdbc:duckdb::memory:'                          | false    | true   | true
        'jdbc:duckdb::memory:x'                         | false    | true   | false
        'jdbc:duckdb:md:my_db'                          | false    | false  | false
        'jdbc:h2:file:/data/x'                          | true     | false  | false
        'jdbc:h2:/data/x;FILE_LOCK=FS'                  | true     | false  | false
        'jdbc:h2:~/x'                                   | true     | false  | false
        'jdbc:h2:mem:'                                  | false    | true   | true
        'jdbc:h2:mem:;DB_CLOSE_DELAY=-1'                | false    | true   | true
        'jdbc:h2:mem:x'                                 | false    | true   | false
        'jdbc:h2:mem:x;DB_CLOSE_DELAY=-1'               | false    | true   | false
        'JDBC:H2:MEM:x'                                 | false    | true   | false
        'jdbc:h2:tcp://localhost/~/x'                   | false    | false  | false
        'jdbc:h2:tcp://localhost/mem:x'                 | false    | false  | false
        'jdbc:h2:ssl://localhost/~/x'                   | false    | false  | false
        'jdbc:sqlite:/data/x.db'                        | true     | false  | false
        'jdbc:sqlite:x.db'                              | true     | false  | false
        'jdbc:sqlite:'                                  | false    | true   | true
        'jdbc:sqlite::memory:'                          | false    | true   | true
        'jdbc:sqlite:file::memory:'                     | false    | true   | true
        'jdbc:sqlite:file::memory:?cache=shared'        | false    | true   | false
        'jdbc:sqlite:file:x?mode=memory'                | false    | true   | true
        'jdbc:sqlite:file:x?mode=memory&cache=shared'   | false    | true   | false
        'jdbc:sqlite::memory:?cache=shared'             | true     | false  | false
        'jdbc:postgresql://localhost/x'                 | false    | false  | false
        null                                            | false    | false  | false
    }

    def 'should not pool private in-memory databases'() {
        given:
        extension(url: url, maxPoolSize: maxPoolSize)
        def ds = ext.dataSourceFromOpts(db: 'test')

        when:
        def created = ext.sqlExecute(db: 'test', statement: 'CREATE TABLE t(id INT)')
        def inserted = ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (1)')
        then: 'each call gets a new database'
        created.success
        !inserted.success
        ext.pools.getPool(ds) == null

        where:
        url                     | maxPoolSize
        'jdbc:h2:mem:'          | null
        'jdbc:h2:mem:'          | 4
        'jdbc:sqlite::memory:'  | null
        'jdbc:sqlite::memory:'  | 4
        'jdbc:duckdb:'          | null
        'jdbc:duckdb:'          | 4
    }

    def 'should never close idle connections to shared in-memory databases'() {
        given:
        def name = UUID.randomUUID().toString().replace('-', '')
        extension(url: url.replace('NAME', name), maxPoolSize: 3)
        def ds = ext.dataSourceFromOpts(db: 'test')

        when:
        assert ext.sqlExecute(db: 'test', statement: 'CREATE TABLE t(id INT)').success
        assert ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (1)').success
        then:
        ext.sqlQuery(db: 'test', query: 'SELECT id FROM t')*.id == [1]
        and:
        def pool = ext.pools.getPool(ds)
        pool.maximumPoolSize == 3
        pool.idleTimeout == 0
        pool.maxLifetime == 0

        where:
        url << [
            'jdbc:h2:mem:NAME',
            'jdbc:h2:mem:NAME;DB_CLOSE_DELAY=-1',
            'jdbc:sqlite:file:NAME?mode=memory&cache=shared',
            'jdbc:duckdb::memory:NAME'
        ]
    }

    def 'should release embedded file databases after each call unless maxPoolSize is set'() {
        given:
        def dir = File.createTempDir()
        def ds = new SqlDataSource(url: url.replace('DIR', dir.toString()), maxPoolSize: maxPoolSize)
        def pools = new ConnectionPools()

        when:
        pools.withConnection(ds, 'file') { Connection conn -> conn.createStatement().execute('CREATE TABLE t(id INT)') }
        then:
        isLocked(ds.url, dir) == locked
        and:
        def pool = pools.getPool(ds)
        locked ? pool.maximumPoolSize == 2 && pool.idleTimeout == TimeUnit.MINUTES.toMillis(10) : pool == null

        cleanup:
        pools.close()
        dir.deleteDir()

        where:
        url                         | maxPoolSize | locked
        'jdbc:h2:DIR/db'            | null        | false
        'jdbc:h2:DIR/db'            | 2           | true
        'jdbc:duckdb:DIR/db.duckdb' | null        | false
        'jdbc:duckdb:DIR/db.duckdb' | 2           | true
    }

    /**
     * Check whether an embedded database file is held open: H2 holds a file
     * lock (which overlaps a lock from the same JVM), and DuckDB refuses a
     * connection with a different configuration to a database that is open.
     */
    private static boolean isLocked(String url, File dir) {
        if( url.startsWith('jdbc:h2:') ) {
            try( final channel = FileChannel.open(new File(dir, 'db.mv.db').toPath(), StandardOpenOption.WRITE) ) {
                channel.tryLock()?.release()
                return false
            }
            catch( OverlappingFileLockException e ) {
                return true
            }
        }
        final props = new Properties()
        props.setProperty('duckdb.read_only', 'true')
        try( final conn = new org.duckdb.DuckDBDriver().connect(url, props) ) {
            return false
        }
        catch( SQLException e ) {
            return true
        }
    }

    def 'should not block other databases while a pool is created'() {
        given:
        def target = createDatabase()
        def connecting = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        registerDriver('jdbc:slow:') { String url ->
            connecting.countDown()
            release.await()
            DriverManager.getConnection(target, 'sa', null)
        }
        def slow = new SqlDataSource(url: 'jdbc:slow:x')
        def fast = new SqlDataSource(url: "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1".toString())
        def pools = new ConnectionPools()
        def result = null
        def thread = Thread.start {
            result = pools.withConnection(slow, 'slow') { Connection conn -> conn.createStatement().withCloseable { it.executeQuery('SELECT 1').with { next(); getInt(1) } } }
        }
        assert connecting.await(10, TimeUnit.SECONDS)

        when: 'the slow database is still connecting'
        def fastResult = pools.withConnection(fast, 'fast') { Connection conn -> 2 }
        then:
        fastResult == 2
        thread.isAlive()
        pools.getPool(slow) == null

        when:
        release.countDown()
        thread.join(10_000)
        then:
        result == 1
        pools.getPool(slow) != null

        cleanup:
        release.countDown()
        pools.close()
    }

    def 'should report connection errors from sqlExecute and sqlQuery'() {
        given:
        extension(url: 'jdbc:h2:tcp://localhost:1/nothing')

        when:
        def result = ext.sqlExecute(db: 'test', statement: 'SELECT 1')
        then:
        !result.success
        result.error.contains('Connection is broken')
        !result.error.contains('Failed to initialize pool')

        when:
        ext.sqlQuery(db: 'test', query: 'SELECT 1')
        then:
        def e = thrown(IllegalStateException)
        e.message.startsWith("sqlQuery failed on db 'test': Connection is broken")
    }

    def 'should report the driver error when the database becomes unreachable'() {
        given:
        def server = Server.createTcpServer('-tcpPort', '0', '-ifNotExists').start()
        def ds = new SqlDataSource(url: "jdbc:h2:tcp://localhost:${server.port}/mem:${UUID.randomUUID()}".toString())
        def pools = new ConnectionPools(2000)
        pools.withConnection(ds, 'test') { Connection conn -> 1 }
        server.stop()
        // wait until the pool validates idle connections when borrowed
        sleep 600

        when:
        pools.withConnection(ds, 'test') { Connection conn -> 1 }
        then:
        def e = thrown(SQLTransientConnectionException)
        e.message.startsWith("Unable to connect to database 'test' within 2s - cause: ")
        e.message.contains('Connection refused')

        cleanup:
        server?.stop()
        pools.close()
    }

    def 'should not pool connections if the driver does not support #method'() {
        given:
        def target = createDatabase()
        registerDriver('jdbc:stub:') { String url -> unsupported(DriverManager.getConnection(target, 'sa', null), [method]) }
        extension(url: 'jdbc:stub:x')

        when:
        def ids = (1..3).collect { sessionId() }
        def result = ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (?, ?)', params: [1, 'x'])
        then:
        ids.unique().size() == 3
        result.success
        count() == 1
        ext.pools.getPool(ext.dataSourceFromOpts(db: 'test')) == null

        where:
        method << ['isValid', 'getAutoCommit']
    }

    def 'should close the pools on session shutdown'() {
        given:
        Runnable shutdownHook = null
        def url = createDatabase()
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: url]]]]
            onShutdown(_) >> { Runnable hook -> shutdownHook = hook }
        }
        ext = new ChannelSqlExtension()
        ext.init(session)
        def ds = ext.dataSourceFromOpts(db: 'test')
        def id = sessionId()
        def pool = ext.pools.getPool(ds)

        when:
        shutdownHook.run()
        then:
        pool.isClosed()
        ext.pools.getPool(ds) == null
        and: 'a call after shutdown uses its own connection'
        sessionId() != id
        ext.pools.getPool(ds) == null
    }

    def 'should execute batches through the pool with a driver that does not support transactions'() {
        given:
        def target = createDatabase()
        def methods = ['setAutoCommit', 'commit', 'rollback', 'setReadOnly', 'setTransactionIsolation', 'setNetworkTimeout', 'getNetworkTimeout']
        registerDriver('jdbc:notx:') { String url -> unsupported(DriverManager.getConnection(target, 'sa', null), methods) }
        extension(url: 'jdbc:notx:x', maxPoolSize: 2)
        def executor = Executors.newFixedThreadPool(8)

        when:
        def batch = ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (?, ?)', batch: [[1, 'a'], [2, 'b']])
        def failed = ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (?, ?)', batch: [[3, 'c'], [1, 'dup']])
        then:
        batch == [success: true, result: 2]
        !failed.success
        and: 'the batch is not atomic'
        count() == 3

        when:
        def futures = (10..49).collect { int i ->
            executor.submit({ ->
                final result = ext.sqlExecute(db: 'test', statement: 'INSERT INTO t VALUES (?, ?)', params: [i, 'x'])
                assert result.success, result.error
                ext.sqlQuery(db: 'test', query: 'SELECT SESSION_ID() AS sid FROM t WHERE id = ?', params: [i]).first().sid
            } as Callable)
        }
        def ids = futures.collect { it.get(20, TimeUnit.SECONDS) }
        then:
        count() == 43
        ids.unique().size() <= 2

        cleanup:
        executor?.shutdownNow()
    }
}
