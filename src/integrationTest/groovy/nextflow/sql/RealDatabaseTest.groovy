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
import nextflow.Global
import nextflow.Session
import nextflow.script.types.Record
import nextflow.sql.config.SqlDataSource
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Run the SQL functions against real databases using testcontainers.
 *
 * The containers are started once in setupSpec, which is not subject
 * to the per-feature timeout.
 */
@Timeout(60)
class RealDatabaseTest extends Specification {

    static class Database {
        String name
        JdbcDatabaseContainer container

        SqlDataSource getDataSource() {
            new SqlDataSource([url: container.jdbcUrl, user: container.username, password: container.password])
        }

        Sql connect() {
            Sql.newInstance(container.jdbcUrl, container.username, container.password)
        }

        String toString() { name }
    }

    @Shared Database postgres = new Database(name: 'postgres', container: new PostgreSQLContainer('postgres:17.11-alpine'))
    @Shared Database mysql = new Database(name: 'mysql', container: new MySQLContainer('mysql:8.4.13'))
    @Shared List<Database> databases = [postgres, mysql]

    private Sql sql

    def setupSpec() {
        databases.parallelStream().forEach { it.container.start() }
    }

    def cleanupSpec() {
        databases.each { it.container.stop() }
    }

    def setup() {
        Global.session = Mock(Session)
    }

    def cleanup() {
        sql?.close()
        Global.session = null
    }

    private void createTable(Database db, String table, String columns) {
        sql = db.connect()
        sql.execute("drop table if exists $table".toString())
        sql.execute("create table $table($columns)".toString())
    }

    def 'should execute DDL statements on #db'() {
        given:
        sql = db.connect()
        sql.execute('drop table if exists exec1')
        def ext = extension(db)

        expect:
        ext.sqlExecute(statement: 'create table exec1(id int primary key)') == [success: true, result: null]
        ext.sqlExecute(statement: 'alter table exec1 add column alpha varchar(255)') == [success: true, result: null]
        sql.rows('select id, alpha from exec1') == []
        ext.sqlExecute(statement: 'drop table exec1') == [success: true, result: null]
        ext.sqlExecute(statement: 'select * from exec1').success == false

        where:
        db << databases
    }

    def 'should execute DML statements on #db'() {
        given:
        createTable(db, 'exec2', 'id int primary key, alpha varchar(255)')
        def ext = extension(db)

        expect:
        ext.sqlExecute(statement: "insert into exec2(id, alpha) values (1, 'a'), (2, 'b'), (3, 'c')") == [success: true, result: 3]
        ext.sqlExecute(statement: "update exec2 set alpha = 'z' where id > 1") == [success: true, result: 2]
        ext.sqlExecute(statement: 'delete from exec2 where id = 1') == [success: true, result: 1]
        ext.sqlExecute(statement: 'delete from exec2 where id = 1') == [success: true, result: 0]
        sql.rows('select id, alpha from exec2 order by id').collect { [it.id, it.alpha] } == [[2, 'z'], [3, 'z']]

        where:
        db << databases
    }

    def 'should execute statements with params and batch on #db'() {
        given:
        createTable(db, 'exec3', 'id int primary key, alpha varchar(255), omega int')
        def ext = extension(db)

        expect:
        ext.sqlExecute(statement: 'insert into exec3(id, alpha, omega) values (?, ?, ?)', params: [1, "it's", null]) == [success: true, result: 1]
        ext.sqlExecute(statement: 'insert into exec3(id, alpha, omega) values (:id, :alpha, :omega)', params: [id: 2, alpha: 'b', omega: 2]) == [success: true, result: 1]
        ext.sqlExecute(statement: 'insert into exec3(id, alpha) values (?, ?)', batch: (3..1200).collect { [it, "r$it"] }) == [success: true, result: 1198]
        ext.sqlExecute(statement: 'update exec3 set omega = :omega where id = :id', batch: [[id: 1, omega: 10], [id: 3, omega: 30], [id: 9999, omega: 0]]) == [success: true, result: 2]

        and: 'a failed batch is rolled back'
        ext.sqlExecute(statement: 'insert into exec3(id, alpha) values (?, ?)', batch: [[5000, 'x'], [1, 'dup']]).success == false
        sql.firstRow('select count(*) as cnt from exec3').cnt == 1200
        sql.rows('select id, alpha, omega from exec3 where id <= 3 order by id').collect { [it.id, it.alpha, it.omega] } == [[1, "it's", 10], [2, 'b', 2], [3, 'r3', 30]]

        where:
        db << databases
    }

    def 'should query rows as records on #db'() {
        given:
        createTable(db, 'query4', 'id int primary key, alpha varchar(255), omega int')
        sql.execute("insert into query4(id, alpha, omega) values (1, 'a', 10), (2, 'b', 20), (3, 'c', 30)")
        def ext = extension(db)

        when:
        def rows = ext.sqlQuery(query: 'select omega, id, alpha from query4 where omega > ? order by id', params: [15])

        then:
        rows == [[omega: 20, id: 2, alpha: 'b'], [omega: 30, id: 3, alpha: 'c']]
        rows.every { it instanceof Record }
        rows[0].keySet().toList() == ['omega', 'id', 'alpha']

        and:
        ext.sqlQuery(query: 'select alpha from query4 where id = :id', params: [id: 1]) == [[alpha: 'a']]
        ext.sqlQuery(query: 'select alpha from query4 where id < 0') == []

        where:
        db << databases
    }

    def 'should keep column labels as reported on #db'() {
        given:
        createTable(db, 'query5', 'id int primary key, alpha varchar(255)')
        sql.execute("insert into query5(id, alpha) values (1, 'a')")
        def ext = extension(db)
        def q = db == mysql ? '`' : '"'

        expect:
        ext.sqlQuery(query: "select id, ALPHA, id as ${q}MixedCase${q}, id as ${q}UPPER${q} from query5".toString()).first().keySet().toList() == KEYS[db.name]

        where:
        db << databases
        // Postgres folds unquoted identifiers to lower case, MySQL returns the labels as written
        KEYS = [postgres: ['id', 'alpha', 'MixedCase', 'UPPER'], mysql: ['id', 'ALPHA', 'MixedCase', 'UPPER']]
    }

    def 'should read rows written by sqlExecute on #db'() {
        given:
        createTable(db, 'query6', 'id int primary key, alpha varchar(255)')
        def ext = extension(db)

        expect:
        ext.sqlExecute(statement: 'insert into query6(id, alpha) values (?, ?)', batch: [[1, 'a'], [2, 'b']]).success
        ext.sqlQuery(query: 'select id, alpha from query6 order by id') == [[id: 1, alpha: 'a'], [id: 2, alpha: 'b']]

        where:
        db << databases
    }

    def 'should materialize arrays, text and bytea on postgres'() {
        given:
        createTable(postgres, 'query7', 'id int primary key, t text, b bytea, a int[], n int[][]')
        sql.execute("insert into query7 values (1, 'some text', '\\x010203', '{1,2,3}', '{{1,2},{3,4}}')")
        def ext = extension(postgres)

        when:
        def row = ext.sqlQuery(query: 'select t, b, a, n from query7').first()

        then:
        row.t == 'some text'
        row.b == [1, 2, 3] as byte[]
        row.a == [1, 2, 3]
        row.n == [[1, 2], [3, 4]]
    }

    private ChannelSqlExtension extension(Database db) {
        final ds = db.dataSource
        final session = Mock(Session) {
            getConfig() >> [sql: [db: [default: [url: ds.url, user: ds.user, password: ds.password]]]]
        }
        final result = new ChannelSqlExtension()
        result.init(session)
        return result
    }
}
