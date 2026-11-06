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

import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import groovy.sql.Sql
import nextflow.Global
import nextflow.Session
import nextflow.sql.config.SqlDataSource
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Tests for the reuse of connections by the SQL functions
 */
@Timeout(10)
class ConnectionCacheTest extends Specification {

    private Sql sql

    def setup() {
        Global.session = Mock(Session)
    }

    def cleanup() {
        sql?.close()
        Global.session = null
    }

    private ChannelSqlExtension extension(String url) {
        final session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: url]]]]
        }
        final result = new ChannelSqlExtension()
        result.init(session)
        return result
    }

    // a named in-memory database, kept open by the test connection
    private String h2() {
        final url = "jdbc:h2:mem:${UUID.randomUUID()}"
        sql = Sql.newInstance(url, 'sa', null)
        sql.execute('CREATE TABLE sample(id INT PRIMARY KEY)')
        return url
    }

    private static int sessionId(ChannelSqlExtension ext) {
        return ext.sqlQuery(db: 'test', query: 'SELECT SESSION_ID() AS sid').first().sid as int
    }

    private static SqlDataSource dataSource(String url) {
        return new SqlDataSource([url: url])
    }

    // a cache that opens the given connections in order
    private static ConnectionCache cache(long validateAfterMs, List<Connection> conns) {
        final queue = new ArrayDeque<Connection>(conns)
        return new ConnectionCache(validateAfterMs) {
            @Override
            protected Connection connect(SqlDataSource ds) { queue.poll() }
        }
    }

    def 'should reuse the connection across sequential calls'() {
        given:
        def ext = extension(h2())

        when:
        def ids = (1..5).collect { i ->
            assert ext.sqlExecute(db: 'test', statement: 'INSERT INTO sample VALUES (?)', params: [i]).success
            sessionId(ext)
        }
        then:
        ids.unique().size() == 1
        sql.firstRow('SELECT COUNT(*) AS n FROM sample').n == 5

        cleanup:
        ext?.close()
    }

    def 'should open a separate connection for concurrent calls'() {
        given:
        def ds = dataSource(h2())
        def cache = new ConnectionCache()
        def started = new CountDownLatch(1)
        def release = new CountDownLatch(1)

        when: 'a call runs while another call holds the connection'
        def first = CompletableFuture.supplyAsync {
            cache.withConnection(ds) { Connection conn ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                conn
            }
        }
        started.await(5, TimeUnit.SECONDS)
        def second = cache.withConnection(ds) { Connection conn -> conn }
        then: 'it does not wait, and its connection is kept'
        !first.isDone()
        cache.getIdle(ds).is(second)

        when: 'the first call finishes'
        release.countDown()
        def firstConn = first.get(5, TimeUnit.SECONDS)
        then: 'its connection is closed, since another connection is idle'
        !firstConn.is(second)
        firstConn.isClosed()
        !second.isClosed()
        cache.withConnection(ds) { Connection conn -> conn }.is(second)

        cleanup:
        cache?.close()
    }

    def 'should close the connection if the call fails'() {
        given:
        def ext = extension(h2())
        def before = sessionId(ext)

        when:
        def result = ext.sqlExecute(db: 'test', statement: 'INSERT INTO missing VALUES (1)')
        then:
        !result.success
        sessionId(ext) != before

        when:
        ext.sqlQuery(db: 'test', query: 'SELECT * FROM missing')
        then:
        thrown(IllegalStateException)
        sessionId(ext) != before

        cleanup:
        ext?.close()
    }

    def 'should close the connection if it is left out of autocommit'() {
        given:
        def ds = dataSource(h2())
        def cache = new ConnectionCache()

        when:
        def conn = cache.withConnection(ds) { Connection c -> c.setAutoCommit(false); c }
        then:
        conn.isClosed()
        cache.getIdle(ds) == null
    }

    def 'should keep the connection if the driver does not support autocommit'() {
        given:
        def conn = Mock(Connection) {
            getAutoCommit() >> { throw new SQLFeatureNotSupportedException() }
        }
        def cache = cache(ConnectionCache.VALIDATE_AFTER_MS, [conn])

        when:
        cache.withConnection(dataSource('jdbc:databricks://host')) { Connection c -> null }
        then:
        cache.getIdle(dataSource('jdbc:databricks://host')).is(conn)
    }

    def 'should replace an idle connection that is no longer valid'() {
        given:
        def stale = Mock(Connection) { getAutoCommit() >> true }
        def fresh = Mock(Connection) { getAutoCommit() >> true }
        def cache = cache(0, [stale, fresh])
        def ds = dataSource('jdbc:postgresql://host/db')
        cache.withConnection(ds) { Connection c -> null }

        when:
        def conn = cache.withConnection(ds) { Connection c -> c }
        then:
        1 * stale.isValid(ConnectionCache.VALIDATE_TIMEOUT_SECS) >> false
        1 * stale.close()
        conn.is(fresh)
    }

    def 'should reuse an idle connection if the driver cannot validate it'() {
        given:
        def conn = Mock(Connection) {
            getAutoCommit() >> true
            isValid(_) >> { throw new SQLFeatureNotSupportedException() }
        }
        def cache = cache(0, [conn])
        def ds = dataSource('jdbc:postgresql://host/db')
        cache.withConnection(ds) { Connection c -> null }

        expect:
        cache.withConnection(ds) { Connection c -> c }.is(conn)
    }

    def 'should not validate a recently used connection'() {
        given:
        def conn = Mock(Connection) { getAutoCommit() >> true }
        def cache = cache(ConnectionCache.VALIDATE_AFTER_MS, [conn])
        def ds = dataSource('jdbc:postgresql://host/db')
        cache.withConnection(ds) { Connection c -> null }

        when:
        def result = cache.withConnection(ds) { Connection c -> c }
        then:
        0 * conn.isValid(_)
        result.is(conn)
    }

    def 'should not keep connections to #driver file databases'() {
        given:
        def dir = Files.createTempDirectory('conncache')
        def ds = dataSource("jdbc:${driver}:${dir.resolve('test.db')}")
        def cache = new ConnectionCache()

        when:
        def conn = cache.withConnection(ds) { Connection c -> c }
        then:
        conn.isClosed()
        cache.getIdle(ds) == null

        cleanup:
        dir.deleteDir()

        where:
        driver << ['sqlite', 'duckdb', 'h2']
    }

    def 'should check whether #url is cacheable'() {
        expect:
        ConnectionCache.isCacheable(url) == expected

        where:
        url                                                     | expected
        'jdbc:postgresql://host/db'                             | true
        'jdbc:databricks://host:443'                            | true
        'jdbc:h2:mem:test;DB_CLOSE_DELAY=-1'                    | true
        'jdbc:h2:tcp://host/~/test'                             | true
        'jdbc:duckdb::memory:shared'                            | true
        'jdbc:duckdb:md:mydb'                                   | true
        'jdbc:sqlite:file::memory:?cache=shared'                | true
        'jdbc:sqlite:file:test?mode=memory&cache=shared'        | true
        'jdbc:h2:mem:'                                          | false
        'jdbc:h2:mem:;MODE=PostgreSQL'                          | false
        'jdbc:h2:./test'                                        | false
        'jdbc:h2:file:/tmp/test'                                | false
        'jdbc:duckdb:'                                          | false
        'jdbc:duckdb::memory:'                                  | false
        'jdbc:duckdb:/tmp/test.db'                              | false
        'jdbc:sqlite::memory:'                                  | false
        'jdbc:sqlite:file::memory:'                             | false
        'jdbc:sqlite:/tmp/test.db'                              | false
        'jdbc:sqlite:file:/tmp/test.db?cache=shared'            | false
    }

    def 'should close the idle connections on session shutdown'() {
        given:
        Runnable shutdownHook = null
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: h2()]]]]
            onShutdown(_) >> { Runnable hook -> shutdownHook = hook }
        }
        def ext = new ChannelSqlExtension()
        ext.init(session)
        def before = sessionId(ext)

        when:
        shutdownHook.run()
        then:
        sql.firstRow('SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.SESSIONS WHERE ID = ?', [before]).n == 0
    }

    def 'should not keep connections after the cache is closed'() {
        given:
        def ds = dataSource(h2())
        def cache = new ConnectionCache()
        cache.close()

        when:
        def conn = cache.withConnection(ds) { Connection c -> c }
        then:
        conn.isClosed()
        cache.getIdle(ds) == null
    }
}
