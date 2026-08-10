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

import java.sql.Connection

import groovy.sql.Sql
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import nextflow.extension.Bolts

/**
 * Model a dataSource configuration
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@ToString(includePackage = false, includeNames = true)
@EqualsAndHashCode
class SqlDataSource {
    public static String DEFAULT_URL = 'jdbc:h2:mem:'
    public static String DEFAULT_DRIVER = 'org.h2.Driver'
    public static String DEFAULT_USER = 'sa'

    static SqlDataSource DEFAULT = new SqlDataSource(Collections.emptyMap())

    String driver
    String url
    String user
    String password
    Map<String,String> properties

    SqlDataSource(Map opts) {
        this.url = opts.url ?: DEFAULT_URL
        this.driver = opts.driver ?: urlToDriver(url) ?: DEFAULT_DRIVER
        this.user = resolveCredential(opts.user, 'user') ?: DEFAULT_USER
        this.password = resolveCredential(opts.password, 'password')
        this.properties = resolveProperties(opts.properties)
    }

    SqlDataSource(Map opts, SqlDataSource fallback) {
        this.url = opts.url ?: fallback.url ?: DEFAULT_URL
        this.driver = opts.driver ?: urlToDriver(url) ?: fallback.driver ?: DEFAULT_DRIVER
        this.user = resolveCredential(opts.user, 'user') ?: fallback.user ?: DEFAULT_USER
        this.password = resolveCredential(opts.password, 'password') ?: fallback.password
        this.properties = opts.properties!=null ? resolveProperties(opts.properties) : fallback.properties
    }

    /**
     * Normalise the JDBC driver properties declared in the config into a map of strings
     *
     * @param value The `properties` config value
     * @return An immutable map of driver properties, or an empty map when not specified
     */
    protected Map<String,String> resolveProperties(Object value) {
        if( !(value instanceof Map) )
            return Collections.<String,String>emptyMap()
        final result = new LinkedHashMap<String,String>()
        for( Map.Entry entry : (value as Map) ) {
            if( entry.value != null )
                result.put(entry.key.toString(), entry.value.toString())
        }
        return Collections.unmodifiableMap(result)
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
                "4. The Nextflow version supports secrets integration (>=25.04.0)\n" +
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

    /**
     * Opens a JDBC connection for this data source.
     *
     * When driver {@code properties} are configured, they are merged with the top-level
     * {@code user}/{@code password} credentials into a single {@link Properties} instance
     * -- with the top-level credentials taking precedence over same-named entries in
     * {@code properties} -- and the connection is opened via the url + Properties + driver
     * overload, because {@code groovy.sql.Sql.newInstance(Map)} does not allow both
     * {@code properties} and {@code user}/{@code password} to be set at the same time.
     * When no properties are configured, the existing map-based connection path is preserved.
     *
     * @return An open {@link Connection}
     */
    Connection getConnection() {
        if( !properties )
            return Sql.newInstance(toMap()).getConnection()

        final props = new Properties()
        props.putAll(properties)
        if( user!=null )
            props.setProperty('user', user)
        if( password!=null )
            props.setProperty('password', password)
        return Sql.newInstance(url, props, driver).getConnection()
    }

    @Override
    String toString() {
        final redactedProps = properties?.collectEntries { k, v -> [k, Bolts.redact(v)] }
        return "SqlDataSource[url=$url; driver=$driver; user=$user; password=${Bolts.redact(password)}; properties=$redactedProps]"
    }
}

