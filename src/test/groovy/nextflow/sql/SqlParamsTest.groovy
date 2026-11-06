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
import spock.lang.Shared
import spock.lang.Specification

class SqlParamsTest extends Specification {

    @Shared Sql sql = Sql.newInstance("jdbc:h2:mem:${UUID.randomUUID()}", 'sa', null)

    def cleanupSpec() {
        sql.close()
    }

    def 'should bind positional params'() {
        given:
        def x = 'b'

        when:
        def bound = new SqlParams(sql, 'insert into t values (?, ?, ?, a[1:2])').bind([1, "$x", null])
        then:
        bound.sql == 'insert into t values (?, ?, ?, a[1:2])'
        bound.params == [1, 'b', null]
        bound.params[1] instanceof String
    }

    def 'should bind named params'() {
        when:
        def bound = new SqlParams(sql, statement).bind([a: 1, b: 'x', c: null])
        then:
        bound.sql == expected
        bound.params == values

        where:
        statement                                          | expected                                          | values
        'insert into t values (:a, :b, :c)'                | 'insert into t values (?, ?, ?)'                  | [1, 'x', null]
        'update t set x = :b where y = :a or z = :a'       | 'update t set x = ? where y = ? or z = ?'         | ['x', 1, 1]
        "select ':a', x::text from t -- :b\nwhere y = :a"  | "select ':a', x::text from t -- :b\nwhere y = ?"  | [1]
    }

    def 'should bind empty map to statement without placeholders'() {
        expect:
        new SqlParams(sql, 'select 1').bind([:]).params == []
    }

    def 'should reject invalid named params'() {
        when:
        new SqlParams(sql, statement).bind(params)
        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(error)

        where:
        statement                    | params   | error
        'select :a, :b'              | [a: 1]   | 'Missing value for named parameter `:b`'
        'select ?, ?'                | [a: 1]   | 'no named placeholders'
        'select :a, ?'               | [a: 1]   | 'mixes positional'
        'select ?'                   | 'x'      | 'must be a list or a map'
    }
}
