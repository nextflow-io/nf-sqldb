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
        def JDBC_URL = 'jdbc:h2:mem:test_ddl_' + Random.newInstance().nextInt(1_000_000)
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
    }

    def 'should execute DML statements successfully and return affected row count'() {
        given:
        def JDBC_URL = 'jdbc:h2:mem:test_dml_' + Random.newInstance().nextInt(1_000_000)
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
    }

    def 'should return correct affected row count for multiple row operations'() {
        given:
        def JDBC_URL = 'jdbc:h2:mem:test_update_' + Random.newInstance().nextInt(1_000_000)
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
    }

    def 'should handle invalid SQL correctly'() {
        given:
        def JDBC_URL = 'jdbc:h2:mem:test_error_' + Random.newInstance().nextInt(1_000_000)
        
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
        def JDBC_URL = 'jdbc:h2:mem:test_norm_' + Random.newInstance().nextInt(1_000_000)
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
    }
} 