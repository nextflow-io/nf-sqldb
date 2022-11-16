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

import groovy.sql.Sql
import nextflow.Channel
import nextflow.plugin.Plugins
import nextflow.plugin.TestPluginDescriptorFinder
import nextflow.plugin.TestPluginManager
import nextflow.plugin.extension.PluginExtensionProvider
import org.pf4j.PluginDescriptorFinder
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Timeout
import test.Dsl2Spec
import test.MockScriptRunner

import java.nio.file.Path

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Timeout(120)
class SqlDslTest extends Dsl2Spec {

    @Shared String pluginsMode

    def setup() {
        // reset previous instances
        PluginExtensionProvider.reset()
        // this need to be set *before* the plugin manager class is created
        pluginsMode = System.getProperty('pf4j.mode')
        System.setProperty('pf4j.mode', 'dev')
        // the plugin root should
        def root = Path.of('.').toAbsolutePath().normalize()
        def manager = new TestPluginManager(root){
            @Override
            protected PluginDescriptorFinder createPluginDescriptorFinder() {
                return new TestPluginDescriptorFinder(){
                    @Override
                    protected Path getManifestPath(Path pluginPath) {
                        return pluginPath.resolve('build/resources/main/META-INF/MANIFEST.MF')
                    }
                }
            }
        }
        Plugins.init(root, 'dev', manager)
    }

    def cleanup() {
        Plugins.stop()
        PluginExtensionProvider.reset()
        pluginsMode ? System.setProperty('pf4j.mode',pluginsMode) : System.clearProperty('pf4j.mode')
    }

    @IgnoreIf({ System.getenv('NXF_SMOKE') })
    @Timeout(60)
    def 'should perform a query for Google BigQuery and create a channel'() {
        given:
        def projectId = System.getenv('NF_SQLDB_TEST_BIGQUERY_PROJECT_ID')
        def serviceAccountEmail = System.getenv('NF_SQLDB_TEST_BIGQUERY_SA_EMAIL')
        def serviceAccountCredsLocation = System.getenv('NF_SQLDB_TEST_BIGQUERY_SA_CREDS_LOCATION')

        def config = [sql: [db: [bigquery: [url: "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=${projectId};OAuthType=0;OAuthServiceAcctEmail=${serviceAccountEmail};OAuthPvtKeyPath=${serviceAccountCredsLocation};"]]]]

        when:
        def SCRIPT = """
            include { fromQuery } from 'plugin/nf-bigquery'

            def sql = \"\"\"
                SELECT *
                FROM `nih-sra-datastore.sra.metadata`
                WHERE organism = 'Mycobacterium tuberculosis'
                AND bioproject = 'PRJNA670836'
                LIMIT 1;
                \"\"\"

            channel.fromQuery(sql, db: "bigquery").view()
            """
        then:
        new MockScriptRunner(config).setScript(SCRIPT).execute()
    }

}
