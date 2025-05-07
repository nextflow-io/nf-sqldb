package nextflow.sql

import spock.lang.Specification
import spock.lang.Requires
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files

class SqlPluginIntegrationTest extends Specification {

    static boolean isNextflowAvailable() {
        try {
            def proc = new ProcessBuilder('nextflow', '--version').start()
            proc.waitFor()
            return proc.exitValue() == 0
        } catch (Exception e) {
            return false
        }
    }

    static boolean hasDatabricksCredentials() {
        def jdbcUrl = System.getenv('DATABRICKS_JDBC_URL')
        def token = System.getenv('DATABRICKS_TOKEN')
        return jdbcUrl && token && !jdbcUrl.isEmpty() && !token.isEmpty()
    }

    @Requires({ isNextflowAvailable() && hasDatabricksCredentials() })
    def 'should run Nextflow pipeline with SQL plugin and Databricks'() {
        given:
        // Ensure test resources directory exists
        def testDir = Paths.get('plugins/nf-sqldb/src/testResources/testDir').toAbsolutePath()
        def scriptPath = testDir.resolve('main.nf')
        def configPath = testDir.resolve('nextflow.config')
        
        // Check if required files exist
        assert Files.exists(testDir), "Test directory doesn't exist: $testDir"
        assert Files.exists(scriptPath), "Script file doesn't exist: $scriptPath"
        assert Files.exists(configPath), "Config file doesn't exist: $configPath"
        
        def env = [
            'DATABRICKS_JDBC_URL': System.getenv('DATABRICKS_JDBC_URL'),
            'DATABRICKS_TOKEN': System.getenv('DATABRICKS_TOKEN')
        ]
        
        when:
        def pb = new ProcessBuilder('nextflow', 'run', scriptPath.toString(), '-c', configPath.toString())
        pb.directory(testDir.toFile())
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        
        def proc = pb.start()
        def output = new StringBuilder()
        proc.inputStream.eachLine { line ->
            println line  // Print output in real-time for debugging
            output.append(line).append('\n')
        }
        def exitCode = proc.waitFor()
        
        then:
        exitCode == 0
        output.toString().contains('alpha') // Should see query result in output
        output.toString().contains('beta')
        output.toString().contains('Updated 1 row(s)')
    }
} 