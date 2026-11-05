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

package nextflow.sql.config

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.extension.Bolts
import nextflow.script.dsl.Description

/**
 * Model a dataSource configuration
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@ToString(includePackage = false, includeNames = true)
@EqualsAndHashCode
class SqlDataSource implements ConfigScope {
    public static String DEFAULT_URL = 'jdbc:h2:mem:'
    public static String DEFAULT_DRIVER = 'org.h2.Driver'
    public static String DEFAULT_USER = 'sa'

    // static getter instead of field, otherwise the config spec would treat it as a nested scope
    static SqlDataSource getDEFAULT() {
        new SqlDataSource(Collections.emptyMap())
    }

    @ConfigOption
    @Description('''
        The database driver class name (optional). If not specified, it is inferred from the JDBC URL for supported databases.
    ''')
    String driver

    @ConfigOption
    @Description('''
        The database connection URL based on the [JDBC standard](https://docs.oracle.com/javase/tutorial/jdbc/basics/connecting.html#db_connection_url).
    ''')
    String url

    @ConfigOption
    @Description('''
        The database connection user name.
    ''')
    String user

    @ConfigOption
    @Description('''
        The database connection password.
    ''')
    String password

    SqlDataSource(Map opts) {
        this.url = opts.url ?: DEFAULT_URL
        this.driver = opts.driver ?: urlToDriver(url) ?: DEFAULT_DRIVER
        this.user = resolveCredential(opts.user, 'user') ?: DEFAULT_USER
        this.password = resolveCredential(opts.password, 'password')
    }

    SqlDataSource(Map opts, SqlDataSource fallback) {
        this.url = opts.url ?: fallback.url ?: DEFAULT_URL
        this.driver = opts.driver ?: urlToDriver(url) ?: fallback.driver ?: DEFAULT_DRIVER
        this.user = resolveCredential(opts.user, 'user') ?: fallback.user ?: DEFAULT_USER
        this.password = resolveCredential(opts.password, 'password') ?: fallback.password
    }

    protected String urlToDriver(String url) {
        DriverRegistry.DEFAULT.urlToDriver(url)
    }

    /**
     * Resolves a credential value, checking for unresolved secrets and providing appropriate error handling
     * 
     * @param value The credential value from configuration
     * @param credType The type of credential ('user' or 'password') for error messages
     * @return The resolved credential value, or null if not provided
     * @throws IllegalArgumentException if an unresolved secret is detected
     */
    protected String resolveCredential(Object value, String credType) {
        if (value == null) {
            return null
        }
        
        String stringValue = value.toString()
        
        // Check for unresolved secrets (patterns like 'secrets.ATHENA_USER' or similar)
        if (stringValue.startsWith('secrets.') || stringValue.contains('secret') && stringValue.contains('[') && stringValue.contains(']')) {
            throw new IllegalArgumentException(
                "Unresolved secret detected for $credType: '$stringValue'. " +
                "This typically indicates that workspace secrets are not properly configured or accessible. " +
                "Please verify that:\n" +
                "1. The secret is defined in your workspace/user secrets\n" +
                "2. The secret name matches exactly (case-sensitive)\n" +
                "3. You have proper permissions to access the secret\n" +
                "See: https://www.nextflow.io/docs/latest/secrets.html"
            )
        }
        
        return stringValue.isEmpty() ? null : stringValue
    }

    Map toMap() {
        final result = new HashMap(10)
        if( url )
            result.url = url
        if( driver )
            result.driver = driver
        if( user || password ) {
            result.user = user
            result.password = password
        }
        return result
    }

    @Override
    String toString() {
        return "SqlDataSource[url=$url; driver=$driver; user=$user; password=${Bolts.redact(password)}]"
    }
}
