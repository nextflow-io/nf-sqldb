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

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.PlaceholderName
import nextflow.config.spec.ScopeName
import nextflow.script.dsl.Description

/**
 * Model SQL dataSources configuration
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@ScopeName('sql')
@Description('''
    The `sql` scope allows you to configure the `nf-sqldb` plugin.
''')
@ToString(includePackage = false, includeNames = true)
@EqualsAndHashCode(includeFields = true)
@CompileStatic
class SqlConfig implements ConfigScope {

    @PlaceholderName('<name>')
    @Description('''
        The databases that can be accessed by name. The `default` database provides fallback values for all other databases.
    ''')
    private Map<String,SqlDataSource> db

    /* required by extension point -- do not remove */
    SqlConfig() {}

    /**
     * @param config The `sql.db` config map
     */
    SqlConfig(Map config) {
        db = parseDataSources(config)
    }

    SqlDataSource getDataSource(String name) {
        return name=='default'
                ? db.get(name) ?: SqlDataSource.DEFAULT
                : db.get(name)
    }

    List<String> getDataSourceNames() {
        return new ArrayList<String>(db.keySet())
    }

    protected Map<String,SqlDataSource> parseDataSources(Map<String,Map> config) {
        // the `default` datasource provides fallback values for the other datasources
        final defSource = new SqlDataSource(config?.'default' as Map ?: Collections.emptyMap())

        final result = new LinkedHashMap<String,SqlDataSource>()
        for( Map.Entry<String,Map> entry : config ) {
            if( entry.key == 'default' ) {
                result.put( entry.key, defSource )
            }
            else {
                result.put( entry.key, new SqlDataSource(entry.value, defSource) )
            }
        }

        return result
    }

}
