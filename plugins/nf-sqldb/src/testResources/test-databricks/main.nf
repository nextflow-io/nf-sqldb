nextflow.enable.dsl=2

include { fromQuery; sqlInsert; sqlExecute } from 'plugin/nf-sqldb'

workflow {
    // Setup: create table (DDL operation)
    def createResult = sqlExecute(
        db: 'foo',
        statement: '''
            CREATE TABLE IF NOT EXISTS testing (
                id INTEGER PRIMARY KEY,
                name VARCHAR(100),
                value DOUBLE
            )
        '''
    )
    println "Create table success: ${createResult.success}" // Should be true
    
    // Handle potential failure
    if (!createResult.success) {
        println "Failed to create table: ${createResult.error}"
        return
    }

    // Insert data using sqlInsert
    Channel
        .of([1, 'alpha', 10.5], [2, 'beta', 20.5])
        .sqlInsert(
            db: 'foo',
            into: 'sample_table',
            columns: 'id, name, value'
        )

    // Query data using fromQuery
    fromQuery('SELECT * FROM sample_table', db: 'foo')
        .view()

    // Update data using sqlExecute (DML operation returns affected row count in result field)
    def updateResult = sqlExecute(
        db: 'foo',
        statement: "UPDATE sample_table SET value = 30.5 WHERE name = 'beta'"
    )
    
    if (updateResult.success) {
        println "Updated ${updateResult.result} row(s)"
    } else {
        println "Update failed: ${updateResult.error}"
    }
} 