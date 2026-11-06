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
 * Run the SQL operations against real databases using testcontainers.
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

    private static List drain(DataflowQueue queue) {
        final result = []
        def value
        while( (value = queue.getVal()) != Channel.STOP )
            result << value
        return result
    }

    def 'should query rows on #db'() {
        given:
        createTable(db, 'query1', 'id int primary key, alpha varchar(255)')
        sql.execute("insert into query1(id, alpha) values (1, 'a'), (2, 'b'), (3, 'c')")

        when:
        def result = new DataflowQueue()
        new QueryHandler()
                .withTarget(result)
                .withStatement('select id, alpha from query1 order by id')
                .withDataSource(db.dataSource)
                .perform()
        then:
        drain(result) == [[1, 'a'], [2, 'b'], [3, 'c']]

        where:
        db << databases
    }

    def 'should query rows with pagination on #db'() {
        given:
        createTable(db, 'query2', 'id int primary key, alpha varchar(255)')
        for( int x : 1..13 )
            sql.execute('insert into query2(id, alpha) values (?, ?)', [x, "Hello $x".toString()])

        when:
        def result = new DataflowQueue()
        def handler = new QueryHandler()
                .withTarget(result)
                .withStatement('select id, alpha from query2 order by id')
                .withDataSource(db.dataSource)
                .withOpts(batchSize: 5)
                .perform()
        then:
        handler.queryCount() == 3
        drain(result) == (1..13).collect { [it, "Hello $it".toString()] }

        where:
        db << databases
    }

    def 'should query rows with column names on #db'() {
        given:
        createTable(db, 'query3', 'id int primary key, alpha varchar(255)')
        sql.execute("insert into query3(id, alpha) values (1, 'a')")

        when:
        def result = new DataflowQueue()
        new QueryHandler()
                .withTarget(result)
                .withStatement('select id, alpha from query3')
                .withDataSource(db.dataSource)
                .withOpts(emitColumns: true)
                .perform()
        then:
        // unlike H2, these databases report lower-case column names
        drain(result) == [['id', 'alpha'], [1, 'a']]

        where:
        db << databases
    }

    def 'should insert tuples with explicit columns on #db'() {
        given:
        createTable(db, 'insert1', 'id int primary key, alpha varchar(255), omega int')

        when:
        def handler = new InsertHandler(db.dataSource, [into: 'insert1', columns: 'id,alpha'])
        handler.perform([1, 'a'])
        handler.perform([2, 'b'])
        handler.close()
        then:
        sql.rows('select id, alpha, omega from insert1 order by id').collect { [it.id, it.alpha, it.omega] } == [[1, 'a', null], [2, 'b', null]]

        where:
        db << databases
    }

    def 'should insert maps with inferred columns on #db'() {
        given:
        createTable(db, 'insert2', 'id int primary key, alpha varchar(255), omega int')

        when:
        def handler = new InsertHandler(db.dataSource, [into: 'insert2'])
        handler.perform([Alpha: 'a', ID: 1])
        handler.perform([Alpha: 'b', ID: 2])
        handler.close()
        then:
        handler.getSqlStatement([:]) == 'insert into insert2 ( id,alpha ) values ( ?,? )'
        sql.rows('select id, alpha, omega from insert2 order by id').collect { [it.id, it.alpha, it.omega] } == [[1, 'a', null], [2, 'b', null]]

        where:
        db << databases
    }

    def 'should insert maps with inferred columns into #into on postgres'() {
        given:
        createTable(postgres, 'insert6', 'id int primary key, alpha varchar(255)')

        when:
        def handler = new InsertHandler(postgres.dataSource, [into: into])
        handler.perform([alpha: 'a', id: 1])
        handler.close()
        then:
        handler.getSqlStatement([:]) == "insert into $into ( id,alpha ) values ( ?,? )"
        sql.rows('select id, alpha from insert6').collect { [it.id, it.alpha] } == [[1, 'a']]

        where:
        into << ['INSERT6', 'Insert6']
    }

    def 'should reject map keys that differ only by case on #db'() {
        given:
        createTable(db, 'insert7', 'id int primary key, alpha varchar(255)')

        when:
        def handler = new InsertHandler(db.dataSource, [into: 'insert7'])
        handler.perform([id: 1, ID: 2, alpha: 'a'])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Keys must be unique ignoring case: id,ID')

        cleanup:
        handler?.close()

        where:
        db << databases
    }

    def 'should match quoted mixed-case columns exactly on postgres'() {
        given:
        createTable(postgres, 'insert8', 'id int primary key, "Alpha" varchar(255)')

        when:
        def handler = new InsertHandler(postgres.dataSource, [into: 'insert8'])
        handler.perform([id: 1, alpha: 'a'])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.startsWith("Unknown columns for table 'insert8': alpha")

        cleanup:
        handler?.close()
    }

    def 'should report unknown map columns on #db'() {
        given:
        createTable(db, 'insert3', 'id int primary key, alpha varchar(255)')

        when:
        def handler = new InsertHandler(db.dataSource, [into: 'insert3'])
        handler.perform([id: 1, beta: 'x'])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.startsWith("Unknown columns for table 'insert3': beta")

        cleanup:
        handler?.close()

        where:
        db << databases
    }

    // MySQL table names are case-sensitive on Linux by default
    def 'should insert tuples with inferred columns into #into on #db'() {
        given:
        createTable(db, 'insert4', 'id int primary key, alpha varchar(255)')

        when:
        def handler = new InsertHandler(db.dataSource, [into: into])
        handler.perform([1, 'a'])
        handler.close()
        then:
        handler.getSqlStatement([]) == "insert into $into ( id,alpha ) values ( ?,? )"
        sql.rows('select id, alpha from insert4').collect { [it.id, it.alpha] } == [[1, 'a']]

        where:
        db       | into
        postgres | 'insert4'
        postgres | 'INSERT4'
        postgres | 'Insert4'
        mysql    | 'insert4'
    }

    private void createSchemaTables(Database db, String schema, String table) {
        sql = db.connect()
        if( db == mysql ) {
            // MySQL schemas are databases, which only root can create
            final root = Sql.newInstance(db.container.jdbcUrl, 'root', db.container.password)
            root.execute("drop database if exists $schema".toString())
            root.execute("create database $schema".toString())
            root.execute("grant all on ${schema}.* to '${db.container.username}'@'%'".toString())
            root.close()
        }
        else {
            sql.execute("drop schema if exists $schema cascade".toString())
            sql.execute("create schema $schema".toString())
        }
        sql.execute("drop table if exists $table".toString())
        sql.execute("create table $table(id int primary key, alpha varchar(255))".toString())
        sql.execute("create table ${schema}.${table}(id int primary key, beta varchar(255))".toString())
    }

    def 'should infer columns only from the table in the current schema on #db'() {
        given:
        createSchemaTables(db, 'schema9', 'insert9')

        when:
        def handler = new InsertHandler(db.dataSource, [into: 'insert9'])
        handler.perform(record)
        handler.close()
        then:
        handler.getSqlStatement(record) == 'insert into insert9 ( id,alpha ) values ( ?,? )'
        sql.rows('select id, alpha from insert9').collect { [it.id, it.alpha] } == [[1, 'a']]

        where:
        db       | record
        postgres | [1, 'a']
        postgres | [id: 1, alpha: 'a']
        mysql    | [1, 'a']
        mysql    | [id: 1, alpha: 'a']
    }

    def 'should infer columns of schema-qualified table #into on #db'() {
        given:
        createSchemaTables(db, 'schema10', 'insert10')

        when:
        def handler = new InsertHandler(db.dataSource, [into: into])
        handler.perform([1, 'b'])
        handler.close()
        then:
        handler.getSqlStatement([]) == "insert into $into ( id,beta ) values ( ?,? )"
        sql.rows('select id, beta from schema10.insert10').collect { [it.id, it.beta] } == [[1, 'b']]
        sql.rows('select id from insert10') == []

        where:
        db       | into
        postgres | 'schema10.insert10'
        postgres | 'SCHEMA10.INSERT10'
        mysql    | 'schema10.insert10'
    }

    def 'should infer columns of a table outside the current schema in the search path on postgres'() {
        given:
        createSchemaTables(postgres, 'schema11', 'insert11')
        sql.execute('drop table schema11.insert11')
        def url = postgres.container.jdbcUrl + '&currentSchema=schema11,public'
        def ds = new SqlDataSource([url: url, user: postgres.container.username, password: postgres.container.password])

        when:
        def handler = new InsertHandler(ds, [into: 'insert11'])
        handler.perform([1, 'a'])
        handler.close()
        then:
        handler.getSqlStatement([]) == 'insert into insert11 ( id,alpha ) values ( ?,? )'
        sql.rows('select id, alpha from public.insert11').collect { [it.id, it.alpha] } == [[1, 'a']]
    }

    def 'should not treat underscore in table name as a wildcard on #db'() {
        given:
        createTable(db, 'w_12', 'id int primary key')
        sql.execute('drop table if exists wx12')
        sql.execute('create table wx12(other int)')

        when:
        def handler = new InsertHandler(db.dataSource, [into: 'w_12'])
        handler.perform([1])
        handler.close()
        then:
        handler.getSqlStatement([]) == 'insert into w_12 ( id ) values ( ? )'
        sql.rows('select id from w_12').id == [1]

        where:
        db << databases
    }

    def 'should insert rows in batches on #db'() {
        given:
        createTable(db, 'insert5', 'id int primary key, alpha varchar(255)')

        when:
        def handler = new InsertHandler(db.dataSource, [into: 'insert5', columns: 'id,alpha', batchSize: 10])
        for( int x : 1..25 )
            handler.perform([x, "Hello $x".toString()])
        then: 'only complete batches are committed'
        sql.firstRow('select count(*) as cnt from insert5').cnt == 20

        when:
        handler.close()
        then: 'the last partial batch is committed on close'
        sql.firstRow('select count(*) as cnt from insert5').cnt == 25

        where:
        db << databases
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
