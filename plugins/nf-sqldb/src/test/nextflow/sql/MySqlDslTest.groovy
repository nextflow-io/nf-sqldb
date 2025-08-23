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

package nextflow.sql

import groovy.sql.Sql
import nextflow.Channel
import nextflow.plugin.Plugins
import nextflow.plugin.TestPluginDescriptorFinder
import nextflow.plugin.TestPluginManager
import nextflow.plugin.extension.PluginExtensionProvider
import org.pf4j.PluginDescriptorFinder
import org.testcontainers.containers.MySQLContainer
import spock.lang.Shared
import spock.lang.Timeout
import test.Dsl2Spec
import test.MockScriptRunner

import java.nio.file.Path

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Timeout(10)
class MySqlDslTest extends SqlDslTest {

    @Shared MySQLContainer db = new MySQLContainer(MySQLContainer.DEFAULT_IMAGE_NAME.withTag("latest"))
            .withDatabaseName("test")
            .withUsername("user1")
            .withPassword("password1")

    def setupSpec(){
        db.start()
    }

    def cleanSpec(){
        db.stop()
    }

    String getJdbcURL(){
        db.jdbcUrl
    }

    String getUsername(){
        db.username
    }

    String getPasword(){
        db.password
    }

}
