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

import groovy.sql.Sql
import nextflow.Global
import nextflow.Session
import nextflow.script.types.Record
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Tests for the sqlQuery function
 */
@Timeout(10)
class SqlQueryTest extends Specification {

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

    private ChannelSqlExtension h2() {
        final url = "jdbc:h2:mem:${UUID.randomUUID()}"
        sql = Sql.newInstance(url, 'sa', null)
        sql.execute('CREATE TABLE sample(id INT PRIMARY KEY, name VARCHAR(255), value DOUBLE)')
        sql.execute("INSERT INTO sample VALUES (1, 'alpha', 10.5), (2, 'beta', 20.5), (3, 'gamma', 30.5)")
        return extension(url)
    }

    def 'should query rows as records'() {
        given:
        def ext = h2()

        when:
        def rows = ext.sqlQuery(db: 'test', query: 'SELECT id, name, value FROM sample ORDER BY id')

        then:
        rows instanceof List
        rows == [
            [id: 1, name: 'alpha', value: 10.5d],
            [id: 2, name: 'beta', value: 20.5d],
            [id: 3, name: 'gamma', value: 30.5d]
        ]
        rows.every { it instanceof Record }
        rows[0].id == 1
        rows[0].name == 'alpha'
    }

    def 'should return immutable records'() {
        given:
        def ext = h2()
        def row = ext.sqlQuery(db: 'test', query: 'SELECT id FROM sample WHERE id = 1').first()

        when:
        row.put('id', 2)

        then:
        thrown(UnsupportedOperationException)
        row.id == 1
    }

    def 'should preserve column order'() {
        given:
        def ext = h2()

        expect:
        ext.sqlQuery(db: 'test', query: 'SELECT value, id, name FROM sample WHERE id = 1').first().keySet().toList() == ['value', 'id', 'name']
        ext.sqlQuery(db: 'test', query: 'SELECT name, value, id FROM sample WHERE id = 1').first().keySet().toList() == ['name', 'value', 'id']
    }

    def 'should query with positional and named params'() {
        given:
        def ext = h2()

        expect:
        ext.sqlQuery(db: 'test', query: 'SELECT id FROM sample WHERE value > ? AND name <> ? ORDER BY id', params: [15, 'gamma']) == [[id: 2]]
        ext.sqlQuery(db: 'test', query: 'SELECT id FROM sample WHERE name = :name OR id = :id ORDER BY id', params: [name: 'gamma', id: 1]) == [[id: 1], [id: 3]]
        ext.sqlQuery(db: 'test', query: 'SELECT name FROM sample WHERE name = ?', params: ["alpha' OR '1'='1"]) == []
        ext.sqlQuery(db: 'test', query: 'SELECT name FROM sample WHERE id = ?', params: ["${1}"]) == [[name: 'alpha']]
    }

    def 'should return empty list for empty result'() {
        given:
        def ext = h2()

        when:
        def rows = ext.sqlQuery(db: 'test', query: 'SELECT id FROM sample WHERE id < 0')

        then:
        rows == []
        rows instanceof List
    }

    def 'should use default database'() {
        given:
        def url = "jdbc:h2:mem:${UUID.randomUUID()}"
        sql = Sql.newInstance(url, 'sa', null)
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [default: [url: url]]]]
        }
        def ext = new ChannelSqlExtension()
        ext.init(session)

        expect:
        ext.sqlQuery(query: 'SELECT 1 AS one') == [[one: 1]]
    }

    def 'should lower-case unquoted labels and preserve quoted labels on H2'() {
        given:
        def ext = h2()
        sql.execute('CREATE TABLE "Quoted"("MixedCase" INT, "lower" INT, "UPPER" INT)')
        sql.execute('INSERT INTO "Quoted" VALUES (1, 2, 3)')

        expect:
        ext.sqlQuery(db: 'test', query: 'SELECT id, NAME, Value AS v, id AS "MixedCase", name AS "lower_alias" FROM sample WHERE id = 1').first().keySet().toList() == ['id', 'name', 'v', 'MixedCase', 'lower_alias']
        ext.sqlQuery(db: 'test', query: 'SELECT * FROM "Quoted"') == [[MixedCase: 1, lower: 2, upper: 3]]
        ext.sqlQuery(db: 'test', query: 'SELECT count(*) AS total FROM sample') == [[total: 3]]
    }

    def 'should map column labels to keys'() {
        expect:
        ChannelSqlExtension.columnKey(LABEL, UPPER) == KEY

        where:
        LABEL       | UPPER | KEY
        'ID'        | true  | 'id'
        'FOO_BAR1'  | true  | 'foo_bar1'
        'MixedCase' | true  | 'MixedCase'
        'lower'     | true  | 'lower'
        'ID'        | false | 'ID'
        'MixedCase' | false | 'MixedCase'
        'lower'     | false | 'lower'
    }

    def 'should reject duplicate column labels'() {
        given:
        def ext = h2()

        when:
        ext.sqlQuery(db: 'test', query: 'SELECT a.id, b.id FROM sample a JOIN sample b ON a.id = b.id')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('duplicate column labels: id')
    }

    def 'should reject labels that collide after case folding'() {
        given:
        def ext = h2()

        when:
        ext.sqlQuery(db: 'test', query: 'SELECT id, name AS "id" FROM sample')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('duplicate column labels: id')
    }

    def 'should materialize large objects and arrays'() {
        given:
        def ext = h2()
        sql.execute('CREATE TABLE lob(id INT, c CLOB, b BLOB, a ARRAY)')
        sql.execute("INSERT INTO lob VALUES (1, 'some text', X'010203', ARRAY[1, 2, 3])")
        sql.execute('INSERT INTO lob VALUES (2, NULL, NULL, NULL)')

        when:
        def rows = ext.sqlQuery(db: 'test', query: 'SELECT c, b, a, CASE WHEN a IS NULL THEN NULL ELSE ARRAY[ARRAY[1, 2], ARRAY[3, 4]] END AS n FROM lob ORDER BY id')

        then:
        rows[0].c == 'some text'
        rows[0].b == [1, 2, 3] as byte[]
        rows[0].a == [1, 2, 3]
        rows[0].a instanceof List
        rows[0].n == [[1, 2], [3, 4]]
        rows[1] == [c: null, b: null, a: null, n: null]
    }

    def 'should read rows written by sqlExecute'() {
        given:
        def ext = h2()

        when:
        def res = ext.sqlExecute(db: 'test', statement: 'INSERT INTO sample (id, name, value) VALUES (?, ?, ?)', params: [4, 'delta', 40.5])
        def rows = ext.sqlQuery(db: 'test', query: 'SELECT name FROM sample WHERE id = :id', params: [id: 4])

        then:
        res.success
        rows == [[name: 'delta']]
    }

    def 'should throw on SQL error'() {
        given:
        def ext = h2()

        when:
        ext.sqlQuery(db: 'test', query: 'SELECT * FROM missing_table')

        then:
        def e = thrown(IllegalStateException)
        e.message.startsWith("sqlQuery failed on db 'test': ")
        e.message.contains('MISSING_TABLE')
        e.cause instanceof java.sql.SQLException
    }

    def 'should throw on invalid options'() {
        given:
        def ext = h2()

        when:
        ext.sqlQuery(OPTS)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(MESSAGE)

        where:
        OPTS                                                        | MESSAGE
        [db: 'test']                                                | "Missing required option 'query'"
        [db: 'test', query: '  ']                                   | "Missing required option 'query'"
        [db: 'test', query: 'SELECT 1', foo: 1]                     | 'foo'
        [db: 'test', query: 'SELECT 1', params: 1]                  | 'params'
        [db: 'missing', query: 'SELECT 1']                          | 'Unknown db name: missing'
        [db: 'test', query: 'SELECT 1 WHERE 1 = :a', params: [b: 1]] | 'Missing value for named parameter `:a`'
    }

    def 'should query #driver'() {
        given:
        def dir = Files.createTempDirectory('sqlquery')
        def url = "jdbc:${driver}:${dir.resolve('test.db')}"
        def ext = extension(url)
        ext.sqlExecute(db: 'test', statement: 'CREATE TABLE sample(id INT, Name VARCHAR(255))')
        ext.sqlExecute(db: 'test', statement: 'INSERT INTO sample VALUES (?, ?)', batch: [[1, 'alpha'], [2, 'beta']])

        expect:
        ext.sqlQuery(db: 'test', query: 'SELECT id, Name, id AS "MixedCase" FROM sample WHERE id > ? ORDER BY id', params: [0]) == [
            [id: 1, Name: 'alpha', MixedCase: 1],
            [id: 2, Name: 'beta', MixedCase: 2]
        ]

        cleanup:
        dir.deleteDir()

        where:
        driver << ['sqlite', 'duckdb']
    }
}
