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

import java.nio.file.Path

import groovy.sql.Sql
import nextflow.sql.config.SqlDataSource
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Run the core InsertHandler tests against the embedded SQLite and DuckDB databases.
 */
class EmbeddedInsertHandlerTest extends Specification {

    @TempDir
    Path tempDir

    private Sql sql
    private InsertHandler handler

    def cleanup() {
        handler?.close()
        sql?.close()
    }

    private SqlDataSource createTable(String driver, String columns) {
        final ds = new SqlDataSource([url: "jdbc:${driver}:${tempDir.resolve('test.db')}".toString()])
        // use the same connection settings as the handler, since DuckDB rejects mixed configurations
        sql = Sql.newInstance(ds.toMap())
        sql.execute("create table foo($columns)".toString())
        return ds
    }

    private List<List> rows(String columns) {
        sql.rows("select $columns from foo order by id".toString()).collect { it.values() as List }
    }

    def 'should insert tuples with explicit columns on #driver'() {
        given:
        def ds = createTable(driver, 'id int primary key, alpha varchar(255), omega int')
        handler = new InsertHandler(ds, [into: 'foo', columns: 'id,alpha'])

        when:
        handler.perform([1, 'a'])
        handler.perform([2, 'b'])
        handler.close()
        then:
        rows('id, alpha, omega') == [[1, 'a', null], [2, 'b', null]]

        where:
        driver << ['sqlite', 'duckdb']
    }

    def 'should insert tuples with inferred columns into #into on #driver'() {
        given:
        def ds = createTable(driver, 'id int primary key, alpha varchar(255)')
        handler = new InsertHandler(ds, [into: into])

        when:
        handler.perform([1, 'a'])
        handler.close()
        then:
        handler.getSqlStatement([]) == "insert into $into ( id,alpha ) values ( ?,? )"
        rows('id, alpha') == [[1, 'a']]

        where:
        driver   | into
        'sqlite' | 'foo'
        'sqlite' | 'FOO'
        'duckdb' | 'foo'
    }

    def 'should insert maps with inferred columns into #into on #driver'() {
        given:
        def ds = createTable(driver, 'id int primary key, alpha varchar(255), omega int')
        handler = new InsertHandler(ds, [into: into])

        when:
        handler.perform([Alpha: 'a', ID: 1])
        handler.perform([Alpha: 'b', ID: 2])
        handler.close()
        then:
        handler.getSqlStatement([:]) == "insert into $into ( id,alpha ) values ( ?,? )"
        rows('id, alpha, omega') == [[1, 'a', null], [2, 'b', null]]

        where:
        driver   | into
        'sqlite' | 'foo'
        'sqlite' | 'FOO'
        'duckdb' | 'foo'
    }

    def 'should insert maps with explicit columns ignoring key case on #driver'() {
        given:
        def ds = createTable(driver, 'id int primary key, alpha varchar(255)')
        handler = new InsertHandler(ds, [into: 'foo', columns: 'id,alpha'])

        when:
        handler.perform([ID: 1, Alpha: 'a'])
        handler.close()
        then:
        rows('id, alpha') == [[1, 'a']]

        where:
        driver << ['sqlite', 'duckdb']
    }

    def 'should report unknown map columns on #driver'() {
        given:
        def ds = createTable(driver, 'id int primary key, alpha varchar(255)')
        handler = new InsertHandler(ds, [into: 'foo'])

        when:
        handler.perform([id: 1, beta: 'x'])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.startsWith("Unknown columns for table 'foo': beta")

        where:
        driver << ['sqlite', 'duckdb']
    }

    // DuckDB reports that it stores mixed-case identifiers, but table lookups in its metadata are case-sensitive
    @PendingFeature
    def 'should infer columns with upper-case table name on duckdb'() {
        given:
        def ds = createTable('duckdb', 'id int primary key, alpha varchar(255)')
        handler = new InsertHandler(ds, [into: 'FOO'])

        when:
        handler.perform([id: 1, alpha: 'a'])
        handler.close()
        then:
        rows('id, alpha') == [[1, 'a']]
    }
}
