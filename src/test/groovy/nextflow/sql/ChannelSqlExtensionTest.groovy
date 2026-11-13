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

import nextflow.Session
import spock.lang.Specification
import spock.lang.Timeout

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Timeout(10)
class ChannelSqlExtensionTest extends Specification {

    def 'should error on unresolved secrets' () {
        given:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [test: [user: 'secrets.ATHENA_USER']]]]
        }

        when:
        new ChannelSqlExtension().init(session)

        then:
        thrown(IllegalArgumentException)
    }

    def 'should handle unknown database' () {
        given:
        def session = Mock(Session) {
            getConfig() >> [sql: [db: [default: [url: 'jdbc:h2:mem:'], postgres: [url: 'jdbc:postgresql:']]]]
        }
        def sqlExtension = new ChannelSqlExtension()
        sqlExtension.init(session)

        when:
        sqlExtension.sqlQuery(db: 'invalid', query: 'select * from table')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Unknown db name: invalid")
        e.message.contains("Available databases:")
    }

}
