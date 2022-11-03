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
import spock.lang.Shared
import spock.lang.Timeout
import test.Dsl2Spec
import test.MockScriptRunner

import java.nio.file.Path

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
abstract class SqlDslTest extends Dsl2Spec {

    @Shared String pluginsMode

    abstract String getJdbcURL()

    abstract String getUsername()

    abstract String getPasword()

    Map getConfiguration(String jdbc){
        [sql: [db: [ds1: [url: jdbc, user: username, password: pasword]]]]
    }

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
    def 'should perform a query and create a channel' () {
        given:
        def JDBC_URL = getJdbcURL()
        def sql = Sql.newInstance(JDBC_URL, getUsername(), getPasword())
        and:
        sql.execute('create table FOO(id int primary key, alpha varchar(255), omega int);')
        sql.execute("insert into FOO (id, alpha, omega) values (1, 'hola', 10) ")
        sql.execute("insert into FOO (id, alpha, omega) values (2, 'ciao', 20) ")
        sql.execute("insert into FOO (id, alpha, omega) values (3, 'hello', 30) ")
        and:
        def config = getConfiguration(JDBC_URL)

        when:
        def SCRIPT = '''
            include { fromQuery; sqlInsert } from 'plugin/nf-sqldb'
            def table = 'FOO'
            def sql = "select * from $table"
            channel.fromQuery(sql, db: "ds1") 
            '''
        and:
        def result = new MockScriptRunner(config).setScript(SCRIPT).execute()

        then:
        result.val == [1, 'hola', 10]
        result.val == [2, 'ciao', 20]
        result.val == [3, 'hello', 30]
        result.val == Channel.STOP

        cleanup:
        sql.execute("drop table FOO")
    }


    def 'should insert channel data into a db table' () {
        given:
        def JDBC_URL = getJdbcURL()
        def sql = Sql.newInstance(JDBC_URL, getUsername(), getPasword())
        and:
        sql.execute('create table FOO(id int primary key, alpha varchar(255), omega int);')
        and:
        def config= getConfiguration(JDBC_URL)

        when:
        def SCRIPT = '''
            include { fromQuery; sqlInsert } from 'plugin/nf-sqldb'
            channel
              .of(100,200,300)
              .sqlInsert(into:"FOO", columns:'id', db:"ds1")
            '''
        and:
        def result = new MockScriptRunner(config).setScript(SCRIPT).execute()
        then:
        result.val == 100
        result.val == 200
        result.val == 300
        result.val == Channel.STOP
        and:
        def rows =  sql.rows("select id from FOO;")
        and:
        rows.size() == 3
        rows.id == [100, 200, 300]

        cleanup:
        sql.execute("drop table FOO")
    }

    def 'should insert channel data into a db table in batches' () {
        given:
        def JDBC_URL = getJdbcURL()
        def sql = Sql.newInstance(JDBC_URL, getUsername(), getPasword())
        and:
        sql.execute('create table FOO(id int primary key, alpha varchar(255), omega int);')
        and:
        def config = getConfiguration(JDBC_URL)

        when:
        def SCRIPT = '''
            include { fromQuery; sqlInsert } from 'plugin/nf-sqldb'
            channel
              .of(100,200,300,400,500)
              .sqlInsert(into:'FOO', columns:'id', db:'ds1', batchSize: 2)
            '''
        and:
        def result = new MockScriptRunner(config).setScript(SCRIPT).execute()
        then:
        result.val == 100
        result.val == 200
        result.val == 300
        result.val == 400
        result.val == 500
        result.val == Channel.STOP
        and:
        def rows =  sql.rows("select id from FOO;")
        and:
        rows.size() == 5
        rows.id == [100, 200, 300, 400, 500]

        cleanup:
        sql.execute("drop table FOO")
    }

    def 'should perform a query with headers and create a channel' () {
        given:
        def JDBC_URL = getJdbcURL()
        def sql = Sql.newInstance(JDBC_URL, getUsername(), getPasword())
        and:
        sql.execute('create table FOO(id int primary key, alpha varchar(255), omega int);')
        sql.execute("insert into FOO (id, alpha, omega) values (1, 'hola', 10) ")
        sql.execute("insert into FOO (id, alpha, omega) values (2, 'ciao', 20) ")
        sql.execute("insert into FOO (id, alpha, omega) values (3, 'hello', 30) ")
        and:
        def config = getConfiguration(JDBC_URL)

        when:
        def SCRIPT = '''
            include { fromQuery; sqlInsert } from 'plugin/nf-sqldb'
            def table = 'FOO'
            def sql = "select * from $table"
            channel.fromQuery(sql, db: "ds1", emitColumns:true) 
            '''
        and:
        def result = new MockScriptRunner(config).setScript(SCRIPT).execute()

        then:
        result.val*.toUpperCase() == ['ID', 'ALPHA', 'OMEGA']
        result.val == [1, 'hola', 10]
        result.val == [2, 'ciao', 20]
        result.val == [3, 'hello', 30]
        result.val == Channel.STOP

        cleanup:
        sql.execute("drop table FOO")
    }
}
