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
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

import groovy.sql.Sql
import nextflow.Channel
import nextflow.Global
import nextflow.Session
import nextflow.extension.CH
import nextflow.util.HashBag
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Tests for the SQL execution functionality (sqlExecute method)
 * 
 * @author Seqera Labs
 */
@Timeout(10)
class SqlExecutionTest extends Specification {

    def setupSpec() {
        // Initialize session for tests
        Global.session = Mock(Session)
    }

    def cleanup() {
        Global.session = null
    }

    def 'should execute DDL statements successfully and return success map'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        
        and:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: JDBC_URL]]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when: 'Creating a table'
        def createResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'CREATE TABLE test_table(id INT PRIMARY KEY, name VARCHAR(255))'
        ])
        
        then: 'Table should be created and result should indicate success'
        sql.rows('SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = \'TEST_TABLE\'').size() > 0
        createResult.success == true
        createResult.result == null
        
        when: 'Altering the table'
        def alterResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'ALTER TABLE test_table ADD COLUMN description VARCHAR(255)'
        ])
        
        then: 'Column should be added and result should indicate success'
        sql.rows('SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = \'TEST_TABLE\' AND COLUMN_NAME = \'DESCRIPTION\'').size() > 0
        alterResult.success == true
        alterResult.result == null
        
        when: 'Dropping the table'
        def dropResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'DROP TABLE test_table'
        ])
        
        then: 'Table should be dropped and result should indicate success'
        sql.rows('SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = \'TEST_TABLE\'').size() == 0
        dropResult.success == true
        dropResult.result == null

        cleanup:
        sql?.close()
    }

    def 'should execute DML statements successfully and return affected row count'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        
        and: 'Create a table'
        sql.execute('CREATE TABLE test_dml(id INT PRIMARY KEY, name VARCHAR(255), value INT)')
        
        and:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: JDBC_URL]]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when: 'Inserting data'
        def insertResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'INSERT INTO test_dml (id, name, value) VALUES (1, \'item1\', 100)'
        ])
        
        then: 'Row should be inserted and result should indicate success with 1 row affected'
        sql.rows('SELECT * FROM test_dml').size() == 1
        sql.firstRow('SELECT * FROM test_dml WHERE id = 1').name == 'item1'
        insertResult.success == true
        insertResult.result == 1
        
        when: 'Updating data'
        def updateResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'UPDATE test_dml SET value = 200 WHERE id = 1'
        ])
        
        then: 'Row should be updated and result should indicate success with 1 row affected'
        sql.firstRow('SELECT value FROM test_dml WHERE id = 1').value == 200
        updateResult.success == true
        updateResult.result == 1
        
        when: 'Deleting data'
        def deleteResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'DELETE FROM test_dml WHERE id = 1'
        ])
        
        then: 'Row should be deleted and result should indicate success with 1 row affected'
        sql.rows('SELECT * FROM test_dml').size() == 0
        deleteResult.success == true
        deleteResult.result == 1

        cleanup:
        sql?.close()
    }

    def 'should return correct affected row count for multiple row operations'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        
        and: 'Create a table and insert multiple rows'
        sql.execute('CREATE TABLE test_update(id INT PRIMARY KEY, name VARCHAR(255), value INT)')
        sql.execute('INSERT INTO test_update VALUES (1, \'item1\', 100)')
        sql.execute('INSERT INTO test_update VALUES (2, \'item2\', 100)')
        sql.execute('INSERT INTO test_update VALUES (3, \'item3\', 100)')
        
        and:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: JDBC_URL]]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when: 'Inserting data'
        def insertResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'INSERT INTO test_update (id, name, value) VALUES (4, \'item4\', 100)'
        ])
        
        then: 'Should return success with 1 affected row'
        insertResult.success == true
        insertResult.result == 1
        sql.rows('SELECT * FROM test_update').size() == 4
        
        when: 'Updating multiple rows'
        def updateResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'UPDATE test_update SET value = 200 WHERE value = 100'
        ])
        
        then: 'Should return success with 4 affected rows'
        updateResult.success == true
        updateResult.result == 4
        sql.rows('SELECT * FROM test_update WHERE value = 200').size() == 4
        
        when: 'Deleting multiple rows'
        def deleteResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'DELETE FROM test_update WHERE value = 200'
        ])
        
        then: 'Should return success with 4 affected rows'
        deleteResult.success == true
        deleteResult.result == 4
        sql.rows('SELECT * FROM test_update').size() == 0

        cleanup:
        sql?.close()
    }

    private ChannelSqlExtension extension(String url) {
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: url]]]]
        }
        def result = new ChannelSqlExtension()
        result.init(session)
        return result
    }

    private static List rows(Sql sql, String table) {
        sql.rows("SELECT id, name, val FROM $table ORDER BY id".toString()).collect { [it.id, it.name, it.val] }
    }

    def 'should not log failed statements with groovy-sql'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def ext = extension(JDBC_URL)
        def records = []
        def handler = new Handler() {
            void publish(LogRecord record) { records << record }
            void flush() {}
            void close() {}
        }
        def logger = Logger.getLogger(Sql.name)
        logger.addHandler(handler)

        when:
        def result = ext.sqlExecute(db: 'test', statement: 'INSERT INTO missing VALUES (1)')
        ext.sqlQuery(db: 'test', query: 'SELECT * FROM missing')
        then:
        thrown(IllegalStateException)
        !result.success
        records.findAll { it.level.intValue() >= Level.WARNING.intValue() } == []

        cleanup:
        logger.removeHandler(handler)
    }

    def 'should execute statement with positional params'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('CREATE TABLE p1(id INT PRIMARY KEY, name VARCHAR(255), val INT)')
        def ext = extension(JDBC_URL)
        def name = 'gamma'

        expect:
        ext.sqlExecute(db: 'test', statement: 'INSERT INTO p1 (id, name, val) VALUES (?, ?, ?)', params: [1, 'alpha', 10]) == [success: true, result: 1]
        ext.sqlExecute(db: 'test', statement: 'INSERT INTO p1 (id, name, val) VALUES (?, ?, ?)', params: [2, "beta", null]) == [success: true, result: 1]
        ext.sqlExecute(db: 'test', statement: 'INSERT INTO p1 (id, name, val) VALUES (?, ?, ?)', params: [3, "${name}", 30]) == [success: true, result: 1]
        ext.sqlExecute(db: 'test', statement: 'UPDATE p1 SET val = ? WHERE val > ?', params: [0, 5]) == [success: true, result: 2]
        rows(sql, 'p1') == [[1, 'alpha', 0], [2, 'beta', null], [3, 'gamma', 0]]

        cleanup:
        sql?.close()
    }

    def 'should execute statement with named params'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('CREATE TABLE p2(id INT PRIMARY KEY, name VARCHAR(255), val INT)')
        def ext = extension(JDBC_URL)

        when:
        def insert = ext.sqlExecute(db: 'test', statement: 'INSERT INTO p2 (id, name, val) VALUES (:id, :name, :val)', params: [val: 10, name: 'alpha', id: 1, extra: 'ignored'])
        def update = ext.sqlExecute(db: 'test', statement: "UPDATE p2 SET name = :name || ' at 12:30' WHERE id = :id OR val = :id", params: [id: 1, name: 'beta'])
        def missing = ext.sqlExecute(db: 'test', statement: 'UPDATE p2 SET val = :val WHERE id = :id', params: [id: 1])
        def positional = ext.sqlExecute(db: 'test', statement: 'UPDATE p2 SET val = ? WHERE id = ?', params: [id: 1])
        then:
        insert == [success: true, result: 1]
        update == [success: true, result: 1]
        missing.success == false
        missing.error.contains('Missing value for named parameter `:val`')
        positional.success == false
        positional.error.contains('no named placeholders')
        rows(sql, 'p2') == [[1, 'beta at 12:30', 10]]

        cleanup:
        sql?.close()
    }

    def 'should bind values with quotes and SQL as plain values'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('CREATE TABLE p3(id INT PRIMARY KEY, name VARCHAR(255), val INT)')
        def ext = extension(JDBC_URL)
        def evil = "x'); DROP TABLE p3; --"

        expect:
        ext.sqlExecute(db: 'test', statement: 'INSERT INTO p3 (id, name) VALUES (?, ?)', params: [1, evil]).success
        ext.sqlExecute(db: 'test', statement: 'INSERT INTO p3 (id, name) VALUES (:id, :name)', params: [id: 2, name: "O'Brien :id ?"]).success
        ext.sqlExecute(db: 'test', statement: 'INSERT INTO p3 (id, name) VALUES (?, ?)', params: [3, 'a[1:2] :name']).success
        rows(sql, 'p3') == [[1, evil, null], [2, "O'Brien :id ?", null], [3, 'a[1:2] :name', null]]

        cleanup:
        sql?.close()
    }

    def 'should execute DDL statement with params'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        def ext = extension(JDBC_URL)

        expect:
        ext.sqlExecute(db: 'test', statement: 'CREATE TABLE p4(id INT PRIMARY KEY)', params: []) == [success: true, result: null]
        ext.sqlExecute(db: 'test', statement: 'CREATE TABLE p5(id INT PRIMARY KEY)', params: [:]) == [success: true, result: null]
        sql.rows("SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN ('P4', 'P5')").size() == 2

        cleanup:
        sql?.close()
    }

    def 'should execute batch with #type rows'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('CREATE TABLE b1(id INT PRIMARY KEY, name VARCHAR(255), val INT)')
        def ext = extension(JDBC_URL)

        when:
        def result = ext.sqlExecute(db: 'test', statement: statement, batch: batch)
        then:
        result == [success: true, result: 2500]
        sql.firstRow('SELECT COUNT(*) AS cnt FROM b1').cnt == 2500
        rows(sql, 'b1').take(2) == [[1, 'r1', null], [2, "r'2", 2]]

        when:
        def update = ext.sqlExecute(db: 'test', statement: 'UPDATE b1 SET val = ? WHERE id <= ?', batch: [[0, 10], [0, 20], [0, 0]])
        then:
        update == [success: true, result: 30]

        cleanup:
        sql?.close()

        where:
        type   | statement                                                  | batch
        'list' | 'INSERT INTO b1 (id, name, val) VALUES (?, ?, ?)'          | (1..2500).collect { [it, it == 2 ? "r'2" : "r$it", it == 1 ? null : it] }
        'map'  | 'INSERT INTO b1 (id, name, val) VALUES (:id, :name, :val)' | (1..2500).collect { [id: it, name: it == 2 ? "r'2" : "r$it", val: it == 1 ? null : it] }
    }

    def 'should roll back batch on error'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('CREATE TABLE b2(id INT PRIMARY KEY, name VARCHAR(255), val INT)')
        def ext = extension(JDBC_URL)

        when: 'a row violates the primary key after the first chunk'
        def result = ext.sqlExecute(db: 'test', statement: 'INSERT INTO b2 (id, name) VALUES (?, ?)', batch: (1..1500).collect { [it, 'x'] } + [[1, 'dup']])
        then:
        result.success == false
        result.error != null
        sql.firstRow('SELECT COUNT(*) AS cnt FROM b2').cnt == 0

        when: 'a row is missing a named value'
        result = ext.sqlExecute(db: 'test', statement: 'INSERT INTO b2 (id, name) VALUES (:id, :name)', batch: [[id: 1, name: 'a'], [id: 2]])
        then:
        result.success == false
        result.error.contains('Missing value for named parameter `:name`')
        sql.firstRow('SELECT COUNT(*) AS cnt FROM b2').cnt == 0

        when: 'the connection is used again'
        result = ext.sqlExecute(db: 'test', statement: 'INSERT INTO b2 (id, name) VALUES (?, ?)', batch: [[1, 'a']])
        then:
        result == [success: true, result: 1]

        cleanup:
        sql?.close()
    }

    def 'should execute empty batch'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('CREATE TABLE b3(id INT PRIMARY KEY)')
        def ext = extension(JDBC_URL)

        expect:
        ext.sqlExecute(db: 'test', statement: 'INSERT INTO b3 (id) VALUES (?)', batch: []) == [success: true, result: 0]

        cleanup:
        sql?.close()
    }

    def 'should execute batch without a transaction if the driver does not support it'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('CREATE TABLE b5(id INT PRIMARY KEY)')
        and: 'a connection that rejects transactions, like Databricks'
        def conn = DriverManager.getConnection(JDBC_URL, 'sa', null)
        def noTx = (Connection) Proxy.newProxyInstance(Connection.classLoader, [Connection] as Class[], { proxy, Method m, Object[] args ->
            if( m.name == 'setAutoCommit' && args[0] == false || m.name in ['commit', 'rollback'] )
                throw new SQLFeatureNotSupportedException(m.name)
            try { return m.invoke(conn, args) }
            catch( InvocationTargetException e ) { throw e.cause }
        } as InvocationHandler)

        expect:
        ChannelSqlExtension.executeBatch(new Sql(noTx), 'INSERT INTO b5 (id) VALUES (?)', [[1], [2], [3]]) == 3
        sql.firstRow('SELECT COUNT(*) AS n FROM b5').n == 3

        cleanup:
        conn?.close()
        sql?.close()
    }

    def 'should pass positional batch statement through unchanged'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('CREATE TABLE b4(id INT PRIMARY KEY, "n:x" VARCHAR(255))')
        def ext = extension(JDBC_URL)

        expect:
        ext.sqlExecute(db: 'test', statement: 'INSERT INTO b4 (id, "n:x") VALUES (?, ?)', batch: [[1, 'a'], [2, 'b']]) == [success: true, result: 2]
        sql.rows('SELECT "n:x" AS n FROM b4 ORDER BY id').collect { it.n } == ['a', 'b']

        cleanup:
        sql?.close()
    }

    def 'should accept any collection as batch'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('CREATE TABLE b6(id INT PRIMARY KEY)')
        def ext = extension(JDBC_URL)

        expect: 'a bag, as emitted by collect under static typing'
        ext.sqlExecute(db: 'test', statement: 'INSERT INTO b6 (id) VALUES (:id)', batch: new HashBag([[id: 1], [id: 2]])) == [success: true, result: 2]
        sql.firstRow('SELECT COUNT(*) AS n FROM b6').n == 2

        cleanup:
        sql?.close()
    }

    def 'should parse named placeholder inside double-quoted identifier'() {
        // documented limitation, use positional params for such statements
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('CREATE TABLE b5(id INT PRIMARY KEY, "n:x" VARCHAR(255))')
        def ext = extension(JDBC_URL)

        when:
        def result = ext.sqlExecute(db: 'test', statement: 'INSERT INTO b5 (id, "n:x") VALUES (:id, :name)', params: [id: 1, name: 'a'])
        then:
        result == [success: false, error: 'Missing value for named parameter `:x`']

        cleanup:
        sql?.close()
    }

    def 'should reject invalid params and batch options'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        sql.execute('CREATE TABLE b4(id INT PRIMARY KEY)')
        def ext = extension(JDBC_URL)

        when:
        def result = ext.sqlExecute([db: 'test', statement: statement] + opts)
        then:
        result.success == false
        result.error.contains(error)
        sql.firstRow('SELECT COUNT(*) AS cnt FROM b4').cnt == 0

        cleanup:
        sql?.close()

        where:
        statement                           | opts                        | error
        'INSERT INTO b4 (id) VALUES (?)'    | [params: [1], batch: [[2]]] | "Options 'params' and 'batch' cannot be used together"
        'INSERT INTO b4 (id) VALUES (?)'    | [params: 'foo']             | "Option 'params' must be a list or a map"
        'INSERT INTO b4 (id) VALUES (?)'    | [params: 1]                 | "Option 'params' must be a list or a map"
        'INSERT INTO b4 (id) VALUES (?)'    | [batch: [1, 2]]             | 'Bind parameters must be a list or a map'
        'INSERT INTO b4 (id) VALUES (?)'    | [batch: [id: 1]]            | "Option 'batch' must be a list"
        'INSERT INTO b4 (id) VALUES (:id)'  | [batch: [[1], [id: 2]]]     | 'Batch rows must be either all lists or all maps'
    }

    def 'should handle invalid SQL correctly'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        
        and:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: JDBC_URL]]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when: 'Executing invalid SQL'
        def invalidResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'INVALID SQL STATEMENT'
        ])
        
        then: 'Should return failure with error message'
        invalidResult.success == false
        invalidResult.error != null
        
        when: 'Executing query with invalid table name'
        def noTableResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'SELECT * FROM non_existent_table'
        ])
        
        then: 'Should return failure with error message'
        noTableResult.success == false
        noTableResult.error != null
    }

    def 'should handle invalid database configuration correctly'() {
        given:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: 'jdbc:h2:mem:test']]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when: 'Using non-existent database alias'
        def nonExistentDbResult = sqlExtension.sqlExecute([
            db: 'non_existent_db',
            statement: 'SELECT 1'
        ])
        
        then: 'Should return failure with error message'
        nonExistentDbResult.success == false
        nonExistentDbResult.error != null
        nonExistentDbResult.error.contains('Unknown db name')
        
        when: 'Missing statement parameter'
        def missingStatementResult = sqlExtension.sqlExecute([
            db: 'test'
        ])
        
        then: 'Should return failure with error message'
        missingStatementResult.success == false
        missingStatementResult.error != null
        missingStatementResult.error.contains('Missing required parameter')
        
        when: 'Empty statement parameter'
        def emptyStatementResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: ''
        ])
        
        then: 'Should return failure with error message'
        emptyStatementResult.success == false
        emptyStatementResult.error != null
        emptyStatementResult.error.contains('Missing required parameter')
    }

    def 'should handle statement normalization correctly'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        
        and:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: JDBC_URL]]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when: 'Executing statement without semicolon'
        def createResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'CREATE TABLE test_norm(id INT PRIMARY KEY)'
        ])
        
        then: 'Statement should be executed successfully'
        sql.rows('SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = \'TEST_NORM\'').size() > 0
        createResult.success == true
        createResult.result == null
        
        when: 'Executing statement with trailing whitespace'
        def dropResult = sqlExtension.sqlExecute([
            db: 'test',
            statement: 'DROP TABLE test_norm  '
        ])
        
        then: 'Statement should be executed successfully'
        sql.rows('SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = \'TEST_NORM\'').size() == 0
        dropResult.success == true
        dropResult.result == null

        cleanup:
        sql?.close()
    }

    def 'should execute statement with trailing semicolon'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: JDBC_URL]]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when:
        def createResult = sqlExtension.sqlExecute([db: 'test', statement: 'CREATE TABLE test_semi(id INT PRIMARY KEY);\n'])
        def insertResult = sqlExtension.sqlExecute([db: 'test', statement: 'INSERT INTO test_semi VALUES (1);'])
        then:
        createResult.success == true
        insertResult.success == true
        insertResult.result == 1

        cleanup:
        sql?.close()
    }

    def 'should detect multi-line DDL statements'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()}"
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: JDBC_URL]]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when:
        def createResult = sqlExtension.sqlExecute([db: 'test', statement: 'CREATE TABLE test_multi (\n  id INT PRIMARY KEY\n)'])
        def alterResult = sqlExtension.sqlExecute([db: 'test', statement: 'alter table test_multi\n  add column name VARCHAR(255)'])
        def insertResult = sqlExtension.sqlExecute([db: 'test', statement: 'INSERT INTO test_multi\nVALUES (1, \'foo\')'])
        def dropResult = sqlExtension.sqlExecute([db: 'test', statement: '\n  Drop\nTABLE test_multi'])
        then:
        createResult == [success: true, result: null]
        alterResult == [success: true, result: null]
        insertResult == [success: true, result: 1]
        dropResult == [success: true, result: null]
        sql.rows('SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = \'TEST_MULTI\'').size() == 0

        cleanup:
        sql?.close()
    }

    // the default database is shared by all tests in the JVM, so use unique table names and drop them
    def 'should keep data in the default database between calls'() {
        given:
        def igniters = []
        def session = Mock(Session) {
            getConfig() >> [:]
            addIgniter(_) >> { Closure action -> igniters << action }
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when:
        def createResult = sqlExtension.sqlExecute([statement: 'CREATE TABLE test_default(id INT PRIMARY KEY, name VARCHAR(255))'])
        def insertResult = sqlExtension.sqlExecute([statement: 'INSERT INTO test_default VALUES (1, \'foo\'), (2, \'bar\')'])
        // subscribe to the channel before running the query
        def query = CH.getReadChannel(sqlExtension.fromQuery('SELECT id, name FROM test_default ORDER BY id'))
        igniters.each { it.call() }
        then:
        createResult == [success: true, result: null]
        insertResult == [success: true, result: 2]
        query.val == [1, 'foo']
        query.val == [2, 'bar']
        query.val == Channel.STOP

        cleanup:
        sqlExtension?.sqlExecute([statement: 'DROP TABLE IF EXISTS test_default'])
    }

    def 'should give a database without url its own in-memory database'() {
        given:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [isolated: [user: 'bob', password: 'pw']]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when:
        def createDefault = sqlExtension.sqlExecute([statement: 'CREATE TABLE test_isolated_default(id INT)'])
        def createOther = sqlExtension.sqlExecute([db: 'isolated', statement: 'CREATE TABLE test_isolated_other(id INT)'])
        then:
        createDefault.success
        createOther.success
        and:
        sqlExtension.sqlExecute([db: 'isolated', statement: 'INSERT INTO test_isolated_default VALUES (1)']).error.contains("not found")
        sqlExtension.sqlExecute([statement: 'INSERT INTO test_isolated_other VALUES (1)']).error.contains("not found")
        sqlExtension.sqlExecute([db: 'isolated', statement: 'INSERT INTO test_isolated_other VALUES (1)']) == [success: true, result: 1]

        cleanup:
        sqlExtension?.closeBuiltInDatabases()
    }

    def 'should inherit the configured default url for a database without url'() {
        given:
        def JDBC_URL = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [default: [url: JDBC_URL], other: [:]]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when:
        sqlExtension.sqlExecute([statement: 'CREATE TABLE test_inherit(id INT)'])
        def result = sqlExtension.sqlExecute([db: 'other', statement: 'INSERT INTO test_inherit VALUES (1)'])
        then:
        result == [success: true, result: 1]

        cleanup:
        sqlExtension?.sqlExecute([statement: 'SHUTDOWN'])
    }

    def 'should close the built-in databases on session shutdown'() {
        given:
        Runnable shutdownHook = null
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [closed: [:]]]]
            onShutdown(_) >> { Runnable hook -> shutdownHook = hook }
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)
        and:
        sqlExtension.sqlExecute([statement: 'CREATE TABLE test_shutdown(id INT)'])
        sqlExtension.sqlExecute([db: 'closed', statement: 'CREATE TABLE test_shutdown(id INT)'])

        when:
        shutdownHook.run()
        then:
        sqlExtension.sqlExecute([statement: 'INSERT INTO test_shutdown VALUES (1)']).error.contains("not found")
        sqlExtension.sqlExecute([db: 'closed', statement: 'INSERT INTO test_shutdown VALUES (1)']).error.contains("not found")
    }
}
