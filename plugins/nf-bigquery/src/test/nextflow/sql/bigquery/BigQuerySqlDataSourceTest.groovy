/*
 * Copyright 2020-2022, Seqera Labs
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

package nextflow.sql.bigquery

import nextflow.sql.BigQueryDriverRegistry
import nextflow.sql.config.DriverRegistry
import nextflow.sql.config.SqlDataSource
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class BigQuerySqlDataSourceTest extends Specification {

    def 'should map url to driver' () {
        given:
        DriverRegistry.DEFAULT = new BigQueryDriverRegistry()
        def helper = new SqlDataSource([:])

        expect:
        helper.urlToDriver(JBDC_URL) == DRIVER
        where:
        JBDC_URL                        | DRIVER
        'jdbc:postgresql:database'      | 'org.postgresql.Driver'
        'jdbc:sqlite:database'          | 'org.sqlite.JDBC'
        'jdbc:h2:mem:'                  | 'org.h2.Driver'
        'jdbc:mysql:some-host'          | 'com.mysql.cj.jdbc.Driver'
        'jdbc:mariadb:other-host'       | 'org.mariadb.jdbc.Driver'
        'jdbc:duckdb:'                  | 'org.duckdb.DuckDBDriver'
        'jdbc:awsathena:'               | 'com.simba.athena.jdbc.Driver'
        'jdbc:bigquery:'                | 'com.simba.googlebigquery.jdbc.Driver'
    }

}
