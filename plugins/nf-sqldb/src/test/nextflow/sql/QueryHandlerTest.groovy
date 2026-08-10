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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import groovy.sql.Sql
import groovyx.gpars.dataflow.DataflowQueue
import nextflow.Channel
import nextflow.Global
import nextflow.Session
import nextflow.sql.config.SqlDataSource
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class QueryHandlerTest extends Specification {

    def 'should normalise query' () {
        given:
        def ext = new QueryHandler()

        expect:
        ext.normalize('select * from x; ') == 'select * from x;'
        ext.normalize('select * from x ')  == 'select * from x;'
    }


    def 'should connect db' () {
        given:
        def ext = new QueryHandler()
        when:
        def conn = ext.connect(new SqlDataSource([:]))
        then:
        conn != null
        cleanup:
        conn.close()
    }

    def 'should perform query' () {
        given:
        def folder = Files.createTempDirectory('test')
        and:
        folder.resolve('test.csv').text  = '''\
        FOO,BAR
        1,hello
        2,ciao
        3,hola
        4,bonjour
        '''.stripIndent()

        when:
        def result = new DataflowQueue()
        def query = "SELECT * FROM CSVREAD('${folder.resolve('test.csv')}') where FOO > 2;"
        new QueryHandler()
                .withTarget(result)
                .withStatement(query)
                .perform()

        then:
        result.val == ['3','hola']
        result.val == ['4','bonjour']
        result.val == Channel.STOP

        cleanup:
        folder.deleteDir()
    }

    def 'should emit header when perform query' () {
        given:
        def folder = Files.createTempDirectory('test')
        and:
        folder.resolve('test.csv').text  = '''\
        FOO,BAR
        1,hello
        2,ciao
        3,hola
        4,bonjour
        '''.stripIndent()

        when:
        def result = new DataflowQueue()
        def query = "SELECT * FROM CSVREAD('${folder.resolve('test.csv')}') where FOO > 2;"
        new QueryHandler()
                .withTarget(result)
                .withStatement(query)
                .withOpts(emitColumns: true)
                .perform()

        then:
        result.val == ['FOO','BAR']
        result.val == ['3','hola']
        result.val == ['4','bonjour']
        result.val == Channel.STOP

        cleanup:
        folder.deleteDir()
    }
    
    def 'should append limit and offset' () {
        given:
        def ext = new QueryHandler()
        
        expect:
        ext.makePaginationStm('select * from FOO')  == 'select * from FOO LIMIT ? OFFSET ?;'
        ext.makePaginationStm('select * from FOO  ;  ')  == 'select * from FOO LIMIT ? OFFSET ?;'

        when:
        ext.makePaginationStm('select * from offset')
        then:
        thrown(IllegalArgumentException)

        when:
        ext.makePaginationStm('select * from limit')
        then:
        thrown(IllegalArgumentException)
    }

    def 'should test paginated query' () {
        given:
        def JDBC_URL = 'jdbc:h2:mem:test_' + Random.newInstance().nextInt(10_000)
        def TABLE = 'create table FOO(id int primary key, alpha varchar(255));'
        def ds = new SqlDataSource([url:JDBC_URL])
        and:
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute(TABLE)
        for( int x : 1..13 ) {
            def params = [x, "Hello $x".toString()]
            sql.execute("insert into FOO (id, alpha) values (?,?);", params)
        }

        when:
        def result = new DataflowQueue()
        def query = "SELECT id, alpha from FOO order by id "
        def handler = new QueryHandler()
                .withTarget(result)
                .withStatement(query)
                .withDataSource(ds)
                .withOpts(batchSize: 5)
                .perform()
        then:
        handler.batchSize() == 5
        handler.queryCount() == 3
        and:
        result.length() == 14  // <-- 13 + the stop signal value
        and:
        result.getVal() == [1, 'Hello 1']
        result.getVal() == [2, 'Hello 2']
        result.getVal() == [3, 'Hello 3']
        result.getVal() == [4, 'Hello 4']
        result.getVal() == [5, 'Hello 5']
        result.getVal() == [6, 'Hello 6']
        result.getVal() == [7, 'Hello 7']
        result.getVal() == [8, 'Hello 8']
        result.getVal() == [9, 'Hello 9']
        result.getVal() == [10, 'Hello 10']
        result.getVal() == [11, 'Hello 11']
        result.getVal() == [12, 'Hello 12']
        result.getVal() == [13, 'Hello 13']
        result.getVal() == Channel.STOP

        when:
        def result2 = new DataflowQueue()
        def query2 = "SELECT id, alpha from FOO order by id "
        new QueryHandler()
                .withTarget(result2)
                .withStatement(query2)
                .withDataSource(ds)
                .withOpts(batchSize: 5, emitColumns: true)
                .perform()
        then:
        result2.length() == 15  // <-- 13 + columns name tuple + the stop signal value
        and:
        result2.getVal() == ['ID', 'ALPHA']
        result2.getVal() == [1, 'Hello 1']
        result2.getVal() == [2, 'Hello 2']
    }

    def 'should cancel executing statement when the session aborts'() {
        given: 'a statement that blocks until cancelled, simulating a long-running query'
        def executing = new CountDownLatch(1)
        def cancelledSignal = new CountDownLatch(1)
        def cancelCount = new java.util.concurrent.atomic.AtomicInteger(0)
        def done = new CountDownLatch(1)
        def statement = Mock(java.sql.Statement) {
            executeQuery(_) >> {
                executing.countDown()
                cancelledSignal.await(5, TimeUnit.SECONDS)
                throw new java.sql.SQLException('cancelled')
            }
            cancel() >> {
                cancelCount.incrementAndGet()
                cancelledSignal.countDown()
            }
        }
        def conn = Mock(java.sql.Connection) {
            createStatement() >> statement
            close() >> { done.countDown() }
        }
        and: 'a handler wired to a live session, as it is when a workflow runs'
        def session = new Session()
        Global.setSession(session)
        def handler = new QueryHandler() {
            protected java.sql.Connection connect(SqlDataSource ds) { conn }
        }
        handler.withTarget(new DataflowQueue()).withStatement('select 1;')

        when: 'the query starts executing asynchronously'
        handler.perform(true)
        and: 'it is actually in flight'
        def reachedExecuting = executing.await(5, TimeUnit.SECONDS)
        and: 'the session aborts, as nextflow does on workflow abort, and runs its shutdown hooks'
        session.abort()
        session.shutdown0()
        and: 'the execution unwinds after being cancelled'
        def reachedDone = done.await(5, TimeUnit.SECONDS)

        then: 'the query actually reached in-flight execution before abort'
        reachedExecuting
        and: 'the connection was actually closed, proving the async task unwound'
        reachedDone
        and: 'the in-flight statement was cancelled exactly once'
        cancelCount.get() == 1

        cleanup:
        Global.setSession(null)
    }

    def 'should ignore a late cancel after the query has completed normally'() {
        given: 'a statement whose query returns no rows'
        def cancelCount = new java.util.concurrent.atomic.AtomicInteger(0)
        def resultSetMeta = Mock(java.sql.ResultSetMetaData) { getColumnCount() >> 0 }
        def resultSet = Mock(java.sql.ResultSet) { next() >> false; getMetaData() >> resultSetMeta }
        def statement = Mock(java.sql.Statement) {
            executeQuery(_) >> resultSet
            cancel() >> { cancelCount.incrementAndGet() }
        }
        def conn = Mock(java.sql.Connection) { createStatement() >> statement }
        def handler = new QueryHandler() {
            protected java.sql.Connection connect(SqlDataSource ds) { conn }
        }
        handler.withTarget(new DataflowQueue()).withStatement('select 1;')

        when: 'the query runs to completion'
        handler.perform()
        and: 'the workflow aborts afterwards'
        handler.cancel()

        then: 'the already-completed statement is never touched'
        cancelCount.get() == 0
    }

    def 'should never execute a statement whose query was cancelled before it existed'() {
        given: 'a statement that must not be executed once cancelled up front'
        def statement = Mock(java.sql.Statement) {
            0 * executeQuery(_)
        }
        def conn = Mock(java.sql.Connection) { createStatement() >> statement }
        def target = new DataflowQueue()
        def handler = new QueryHandler() {
            protected java.sql.Connection connect(SqlDataSource ds) { conn }
        }
        handler.withTarget(target).withStatement('select 1;')
        and: 'the query is cancelled before it starts, e.g. an abort racing the query creation'
        handler.cancel()

        when:
        handler.perform()

        then: 'the statement is never executed and the channel still terminates'
        target.getVal() == Channel.STOP
    }

    def 'should stop a paginated query mid-batch when cancelled between pages'() {
        given: 'a batched query that is cancelled while consuming the first page, before the second page is fetched'
        def cancelCount = new java.util.concurrent.atomic.AtomicInteger(0)
        def executedPages = new java.util.concurrent.atomic.AtomicInteger(0)
        def handler
        def resultSetMeta = Mock(java.sql.ResultSetMetaData) { getColumnCount() >> 1 }
        def fullResultSet = Mock(java.sql.ResultSet) {
            getMetaData() >> resultSetMeta
            next() >> true >> true >> true >> false
            getObject(1) >> 'a' >> 'b' >> {
                // cancel arrives while the statement is idle, in between rows of the current page -
                // it must stop the pagination loop even though the statement isn't executing a command
                handler.cancel()
                'c'
            }
        }
        def statement = Mock(java.sql.PreparedStatement) {
            executeQuery() >> {
                executedPages.incrementAndGet()
                fullResultSet
            }
            cancel() >> { cancelCount.incrementAndGet() }
        }
        def conn = Mock(java.sql.Connection) { prepareStatement(_) >> statement }
        def target = new DataflowQueue()
        handler = new QueryHandler() {
            protected java.sql.Connection connect(SqlDataSource ds) { conn }
        }
        handler.withTarget(target).withStatement('select 1').withOpts(batchSize: 3, batchDelay: 0)

        when:
        handler.perform()

        then: 'only the first page executes; the loop checks cancellation before fetching the next one'
        executedPages.get() == 1
        and: 'the cancel request reached the tracked statement'
        cancelCount.get() == 1
        and: 'the channel is still terminated after the (partial) page rows'
        target.getVal() == ['a']
        target.getVal() == ['b']
        target.getVal() == ['c']
        target.getVal() == Channel.STOP
    }

    def 'should never execute a paginated query page when cancelled during page setup'() {
        given: 'a statement cancelled while binding params for the first page, before executeQuery runs'
        def handler
        def statement = Mock(java.sql.PreparedStatement) {
            setInt(2, 0) >> { handler.cancel() }
            0 * executeQuery()
        }
        def conn = Mock(java.sql.Connection) {
            prepareStatement(_) >> statement
        }
        def target = new DataflowQueue()
        handler = new QueryHandler() {
            protected java.sql.Connection connect(SqlDataSource ds) { conn }
        }
        handler.withTarget(target).withStatement('select 1').withOpts(batchSize: 3, batchDelay: 0)

        when:
        handler.perform()

        then: 'the page is never executed and the channel still terminates'
        target.getVal() == Channel.STOP
    }
}
