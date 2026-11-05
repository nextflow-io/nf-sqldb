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
import nextflow.sql.config.SqlDataSource
import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class InsertHandlerTest extends Specification {

    def 'should get sql insert from statement' () {
        given:
        def DML = 'insert into foo xyz'
        def handler = new InsertHandler(Mock(SqlDataSource), [statement: DML])
        expect:
        handler.getSqlStatement(null) == DML
    }

    def 'should get sql insert from table and columns' () {
        when:
        def handler1 = new InsertHandler(Mock(SqlDataSource), [into: 'FOO', columns:['x1', 'y2', 'z3']])
        then:
        handler1.getSqlStatement([]) == 'insert into FOO ( x1,y2,z3 ) values ( ?,?,? )'

        when:
        def handler2 = new InsertHandler(Mock(SqlDataSource), [into: 'FOO', columns:'a,b,c'])
        then:
        handler2.getSqlStatement([]) == 'insert into FOO ( a,b,c ) values ( ?,?,? )'
    }

    def 'should fetch columns names from db' () {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def TABLE = 'create table FOO(id int primary key, alpha varchar(255), omega int);'
        and:
        def ds = new SqlDataSource([url:JDBC_URL])

        when:
        def handler = new InsertHandler(ds, [into: 'FOO', setup: TABLE])
        then:
        handler.getSqlStatement([]) == 'insert into FOO ( ID,ALPHA,OMEGA ) values ( ?,?,? )'

        cleanup:
        handler?.close()
    }

    def 'should insert data into table' () {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        def TABLE = 'create table FOO(id int primary key, alpha varchar(255), omega int);'
        and:
        def ds = new SqlDataSource([url:JDBC_URL])
        and:
        def handler = new InsertHandler(ds, [into: 'FOO', setup: TABLE])

        when:
        handler.perform([1, 'Hello world', 100])
        handler.perform([2, 'Hola mundo', 200])
        handler.perform([3, 'Ciao mondo', 300])
        and:
        handler.close()
        then:
        def result = sql.rows('select * from FOO')
        result.size() == 3
        and:
        result.id == [1,2,3]
        result.alpha == ['Hello world','Hola mundo','Ciao mondo']
        result.omega == [100,200,300]

        cleanup:
        sql?.close()
    }

    def 'should insert single value into table' () {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def TABLE = 'create table FOO(id int primary key, alpha varchar(255), omega int);'
        and:
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        def ds = new SqlDataSource([url:JDBC_URL])
        and:
        def handler = new InsertHandler(ds, [into: 'FOO', columns: 'id', setup: TABLE])

        when:
        handler.perform(1)
        handler.perform(2)
        handler.perform(3)
        and:
        handler.close()
        then:
        def result = sql.rows('select id from FOO')
        result.size() == 3
        and:
        result.id == [1,2,3]

        cleanup:
        sql?.close()
    }

    def 'should insert map into table' () {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def TABLE = 'create table FOO(id int primary key, alpha varchar(255), omega int);'
        and:
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        def ds = new SqlDataSource([url:JDBC_URL])
        and:
        def handler = new InsertHandler(ds, [into: 'FOO', columns: 'id,alpha', setup: TABLE ])

        when:
        handler.perform([id: 1, alpha: 'Hola'])
        handler.perform([id: 2, alpha: 'Ciao'])
        handler.perform([id: 3, alpha: 'Hello'])
        and:
        handler.close()
        then:
        def result = sql.rows('select id, alpha from FOO')
        result.size() == 3
        and:
        result.id == [1,2,3]
        result.alpha == ['Hola', 'Ciao', 'Hello']

        cleanup:
        sql?.close()
    }

    def 'should insert map with inferred columns ignoring case' () {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}${OPTS}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('create table foo(id int primary key, alpha varchar(255), omega int)')
        and:
        def handler = new InsertHandler(new SqlDataSource([url:JDBC_URL]), [into: TABLE])

        when:
        handler.perform([alpha: 'Hola', ID: 1])
        handler.perform([alpha: 'Ciao', ID: 2])
        handler.close()
        then:
        handler.getSqlStatement([:]) == "insert into $TABLE ( $COLS ) values ( ?,? )"
        and:
        def result = sql.rows('select * from foo order by id')
        result.id == [1, 2]
        result.alpha == ['Hola', 'Ciao']
        result.omega == [null, null]

        cleanup:
        sql?.close()

        where:
        OPTS                       | TABLE | COLS
        ''                         | 'foo' | 'ID,ALPHA'
        ''                         | 'FOO' | 'ID,ALPHA'
        ';DATABASE_TO_LOWER=TRUE'  | 'foo' | 'id,alpha'
        ';DATABASE_TO_LOWER=TRUE'  | 'FOO' | 'id,alpha'
    }

    def 'should insert tuple with inferred columns ignoring table case' () {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}${OPTS}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('create table foo(id int primary key, alpha varchar(255))')
        and:
        def handler = new InsertHandler(new SqlDataSource([url:JDBC_URL]), [into: TABLE])

        when:
        handler.perform([1, 'Hola'])
        handler.close()
        then:
        handler.getSqlStatement([]) == "insert into $TABLE ( $COLS ) values ( ?,? )"
        sql.rows('select * from foo').id == [1]

        cleanup:
        sql?.close()

        where:
        OPTS                       | TABLE | COLS
        ''                         | 'foo' | 'ID,ALPHA'
        ';DATABASE_TO_LOWER=TRUE'  | 'foo' | 'id,alpha'
        ';DATABASE_TO_LOWER=TRUE'  | 'FOO' | 'id,alpha'
    }

    def 'should report unknown map columns' () {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('create table foo(id int primary key, alpha varchar(255))')
        and:
        def handler = new InsertHandler(new SqlDataSource([url:JDBC_URL]), [into: 'foo'])

        when:
        handler.perform([id: 1, Beta: 'x'])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.startsWith("Unknown columns for table 'foo': Beta")

        cleanup:
        handler?.close()
        sql?.close()
    }

}
