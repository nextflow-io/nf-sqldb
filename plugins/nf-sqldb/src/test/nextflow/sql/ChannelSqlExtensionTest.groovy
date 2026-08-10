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

import groovy.sql.Sql
import groovyx.gpars.dataflow.DataflowQueue
import nextflow.Channel
import nextflow.Session
import spock.lang.Specification
import spock.lang.Timeout

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Timeout(10)
class ChannelSqlExtensionTest extends Specification {

    def 'should create channel from query' () {
        given:
        def JDBC_URL = 'jdbc:h2:mem:test_' + Random.newInstance().nextInt(1_000_000)
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        and:
        sql.execute('create table FOO(id int primary key, alpha varchar(255), omega int);')
        sql.execute("insert into FOO (id, alpha, omega) values (1, 'hola', 10) ")
        sql.execute("insert into FOO (id, alpha, omega) values (2, 'ciao', 20) ")
        sql.execute("insert into FOO (id, alpha, omega) values (3, 'hello', 30) ")
        and:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: JDBC_URL]]]]
        }
        def sqlExtension = new ChannelSqlExtension(); sqlExtension.init(session)

        when:
        def result = sqlExtension.fromQuery('select * from FOO', db: 'test')
        then:
        result.val == [1, 'hola', 10]
        result.val == [2, 'ciao', 20]
        result.val == [3, 'hello', 30]
        result.val == Channel.STOP

        when:
        result = sqlExtension.fromQuery('select alpha, omega from FOO where id=3', db: 'test')
        then:
        result.val == ['hello', 30]
        result.val == Channel.STOP
    }


    def 'should insert data into table' () {
        given:
        def JDBC_URL = 'jdbc:h2:mem:test_' + Random.newInstance().nextInt(1_000_000)
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        and:
        sql.execute('create table FOO(id int primary key, alpha varchar(255), omega int);')
        and:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: JDBC_URL]]]]
        }
        def sqlExtension = new ChannelSqlExtension(); sqlExtension.init(session)
        and:
        def ch = new DataflowQueue()
        ch.bind( [1, 'x1'] )
        ch.bind( [2, 'y2'] )
        ch.bind( [3, 'z3'] )
        ch.bind( Channel.STOP )

        when:
        def ret = sqlExtension.sqlInsert(ch, [db: 'test', into:'FOO', columns: 'id, alpha'])
        then:
        ret.val == [1, 'x1']
        ret.val == [2, 'y2']
        ret.val == [3, 'z3']
        ret.val == Channel.STOP
        and:
        def rows = sql.rows("select id, alpha from FOO")
        and:
        rows.size() == 3
        rows.id == [1,2,3]
        rows.alpha == ['x1','y2','z3']
    }

    def 'should error on unresolved secrets' () {
        given:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [user: 'secrets.ATHENA_USER']]]]
        }

        when:
        new ChannelSqlExtension().init(session)

        then:
        thrown(IllegalArgumentException)
    }

    def 'should handle unknown database' () {
        given:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [default: [url: 'jdbc:h2:mem:'], postgres: [url: 'jdbc:postgresql:']]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when:
        sqlExtension.fromQuery([db: 'invalid'], 'select * from table')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Unknown db name: invalid")
        e.message.contains("Available databases:")
    }

    def cleanup() {
        ChannelSqlExtension.unregisterQueryOpProvider()
    }

    def 'should use default QueryHandler when no provider is registered' () {
        given:
        def JDBC_URL = 'jdbc:h2:mem:test_' + Random.newInstance().nextInt(1_000_000)
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        and:
        sql.execute('create table FOO(id int primary key, alpha varchar(255));')
        sql.execute("insert into FOO (id, alpha) values (1, 'hola') ")
        and:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: JDBC_URL]]]]
        }
        def sqlExtension = new ChannelSqlExtension(); sqlExtension.init(session)

        expect:
        sqlExtension.createQueryOp() instanceof QueryHandler

        when:
        def result = sqlExtension.fromQuery('select * from FOO', db: 'test')
        then:
        result.val == [1, 'hola']
        result.val == Channel.STOP
    }

    def 'should warn when overwriting a competing provider' () {
        given:
        def opA = { -> Mock(QueryOp) }
        def opB = { -> Mock(QueryOp) }

        when:
        ChannelSqlExtension.registerQueryOpProvider(opA)
        ChannelSqlExtension.registerQueryOpProvider(opB)

        then:
        noExceptionThrown()
    }

    def 'should use custom QueryOp when a provider is registered' () {
        given:
        def customOp = Mock(QueryOp) {
            withDataSource(_) >> it
            withStatement(_) >> it
            withTarget(_) >> it
            withOpts(_) >> it
        }
        ChannelSqlExtension.registerQueryOpProvider({ -> customOp })
        and:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [default: [url: 'jdbc:h2:mem:custom_op_test']]]]
        }
        def sqlExtension = new ChannelSqlExtension(); sqlExtension.init(session)

        when:
        sqlExtension.fromQuery('select * from FOO')

        then:
        1 * customOp.perform(true)
    }

}
