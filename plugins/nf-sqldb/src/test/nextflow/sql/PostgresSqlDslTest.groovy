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


import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Shared
import spock.lang.Timeout

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Timeout(10)
class PostgresSqlDslTest extends SqlDslTest {

    @Shared PostgreSQLContainer db = new PostgreSQLContainer(PostgreSQLContainer.DEFAULT_IMAGE_NAME)
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
