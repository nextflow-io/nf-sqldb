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
 * Tests for the SQL execution functionality (execute and executeUpdate methods)
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

    def 'should execute DDL statements successfully'() {
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
        sqlExtension.execute([
            db: 'test',
            statement: 'CREATE TABLE test_table(id INT PRIMARY KEY, name VARCHAR(255))'
        ])
        
        then: 'Table should be created'
        sql.rows('SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = \'TEST_TABLE\'').size() > 0
        
        when: 'Altering the table'
        sqlExtension.execute([
            db: 'test',
            statement: 'ALTER TABLE test_table ADD COLUMN description VARCHAR(255)'
        ])
        
        then: 'Column should be added'
        sql.rows('SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = \'TEST_TABLE\' AND COLUMN_NAME = \'DESCRIPTION\'').size() > 0
        
        when: 'Dropping the table'
        sqlExtension.execute([
            db: 'test',
            statement: 'DROP TABLE test_table'
        ])
        
        then: 'Table should be dropped'
        sql.rows('SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = \'TEST_TABLE\'').size() == 0
    }

    def 'should execute DML statements successfully'() {
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
        sqlExtension.execute([
            db: 'test',
            statement: 'INSERT INTO test_dml (id, name, value) VALUES (1, \'item1\', 100)'
        ])
        
        then: 'Row should be inserted'
        sql.rows('SELECT * FROM test_dml').size() == 1
        sql.firstRow('SELECT * FROM test_dml WHERE id = 1').name == 'item1'
        
        when: 'Updating data'
        sqlExtension.execute([
            db: 'test',
            statement: 'UPDATE test_dml SET value = 200 WHERE id = 1'
        ])
        
        then: 'Row should be updated'
        sql.firstRow('SELECT value FROM test_dml WHERE id = 1').value == 200
        
        when: 'Deleting data'
        sqlExtension.execute([
            db: 'test',
            statement: 'DELETE FROM test_dml WHERE id = 1'
        ])
        
        then: 'Row should be deleted'
        sql.rows('SELECT * FROM test_dml').size() == 0
    }

    def 'should return affected row count with executeUpdate'() {
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

        when: 'Inserting data with executeUpdate'
        def insertCount = sqlExtension.executeUpdate([
            db: 'test',
            statement: 'INSERT INTO test_update (id, name, value) VALUES (4, \'item4\', 100)'
        ])
        
        then: 'Should return 1 affected row'
        insertCount == 1
        sql.rows('SELECT * FROM test_update').size() == 4
        
        when: 'Updating multiple rows'
        def updateCount = sqlExtension.executeUpdate([
            db: 'test',
            statement: 'UPDATE test_update SET value = 200 WHERE value = 100'
        ])
        
        then: 'Should return 4 affected rows'
        updateCount == 4
        sql.rows('SELECT * FROM test_update WHERE value = 200').size() == 4
        
        when: 'Deleting multiple rows'
        def deleteCount = sqlExtension.executeUpdate([
            db: 'test',
            statement: 'DELETE FROM test_update WHERE value = 200'
        ])
        
        then: 'Should return 4 affected rows'
        deleteCount == 4
        sql.rows('SELECT * FROM test_update').size() == 0
    }

    def 'should handle invalid SQL correctly'() {
        given:
        def JDBC_URL = 'jdbc:h2:mem:test_error_' + Random.newInstance().nextInt(1_000_000)
        def sql = Sql.newInstance(JDBC_URL, 'sa', null)
        
        and:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: JDBC_URL]]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when: 'Executing invalid SQL'
        sqlExtension.execute([
            db: 'test',
            statement: 'INVALID SQL STATEMENT'
        ])
        
        then: 'Should throw an exception'
        thrown(Exception)
        
        when: 'Executing query with invalid table name'
        sqlExtension.execute([
            db: 'test',
            statement: 'SELECT * FROM non_existent_table'
        ])
        
        then: 'Should throw an exception'
        thrown(Exception)
    }

    def 'should handle invalid database configuration correctly'() {
        given:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [url: 'jdbc:h2:mem:test']]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when: 'Using non-existent database alias'
        sqlExtension.execute([
            db: 'non_existent_db',
            statement: 'SELECT 1'
        ])
        
        then: 'Should throw an IllegalArgumentException'
        thrown(IllegalArgumentException)
        
        when: 'Missing statement parameter'
        sqlExtension.execute([
            db: 'test'
        ])
        
        then: 'Should throw an IllegalArgumentException'
        thrown(IllegalArgumentException)
        
        when: 'Empty statement parameter'
        sqlExtension.execute([
            db: 'test',
            statement: ''
        ])
        
        then: 'Should throw an IllegalArgumentException'
        thrown(IllegalArgumentException)
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
        sqlExtension.execute([
            db: 'test',
            statement: 'CREATE TABLE test_norm(id INT PRIMARY KEY)'
        ])
        
        then: 'Statement should be executed successfully'
        sql.rows('SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = \'TEST_NORM\'').size() > 0
        
        when: 'Executing statement with trailing whitespace'
        sqlExtension.execute([
            db: 'test',
            statement: 'DROP TABLE test_norm  '
        ])
        
        then: 'Statement should be executed successfully'
        sql.rows('SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = \'TEST_NORM\'').size() == 0
    }
} 