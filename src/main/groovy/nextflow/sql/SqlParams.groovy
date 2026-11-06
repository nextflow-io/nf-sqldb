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
import groovy.sql.SqlWithParams
import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/**
 * Bind parameters for a SQL statement: a list for positional `?`
 * placeholders or a map for named `:name` placeholders.
 *
 * Bound statements should be executed with named query parsing
 * disabled (see {@link Sql#setEnableNamedQueries}).
 */
@PackageScope
@CompileStatic
class SqlParams {

    private final String statement

    private final String namedStatement

    // null if there are no named placeholders, null entry for a mixed-in `?`
    private final List<String> names

    SqlParams(Sql sql, String statement) {
        this.statement = statement
        final recorder = new NameRecorder()
        final input = [(Object) recorder]
        final parsed = sql.checkForNamedParams(statement, input)
        if( parsed.params.is(input) ) {
            this.namedStatement = null
            this.names = null
        }
        else {
            this.namedStatement = parsed.sql
            this.names = parsed.params.collect { it instanceof Name ? ((Name) it).name : null }
        }
    }

    /**
     * Get the statement and positional values for the given parameters.
     *
     * @param params a list of positional values or a map of named values
     */
    SqlWithParams bind(Object params) {
        if( params instanceof List )
            return new SqlWithParams(statement, ((List) params).collect { normalize(it) })
        if( params instanceof Map )
            return new SqlWithParams(namedStatement ?: statement, namedValues((Map) params))
        throw new IllegalArgumentException("Bind parameters must be a list or a map, not ${params?.getClass()?.simpleName}")
    }

    private List<Object> namedValues(Map params) {
        if( names == null ) {
            if( params )
                throw new IllegalArgumentException("Statement has no named placeholders (e.g. `:name`) for the given parameters")
            return []
        }
        return names.collect { name ->
            if( name == null )
                throw new IllegalArgumentException("Statement mixes positional `?` and named placeholders")
            if( !params.containsKey(name) )
                throw new IllegalArgumentException("Missing value for named parameter `:${name}`")
            normalize(params.get(name))
        }
    }

    // GStrings are not recognized by JDBC drivers
    private static Object normalize(Object value) {
        return value instanceof GString ? value.toString() : value
    }

    private static class Name {
        final String name
        Name(String name) { this.name = name }
    }

    /**
     * Groovy resolves each named placeholder as a property of the
     * parameter, which for a map is a key lookup. Record the names
     * in place of the values.
     */
    private static class NameRecorder extends HashMap<String,Object> {
        @Override
        Object get(Object key) { new Name(key as String) }
    }
}
