nextflow.enable.dsl=2

include { fromQuery; sqlInsert; execute; executeUpdate } from 'plugin/nf-sqldb'

workflow {
    // Setup: create table
    execute(
        db: 'foo',
        statement: '''
            CREATE TABLE IF NOT EXISTS sample_table (
                id INTEGER PRIMARY KEY,
                name VARCHAR(100),
                value DOUBLE
            )
        '''
    )

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

    // Update data using executeUpdate
    def updated = executeUpdate(
        db: 'foo',
        statement: "UPDATE sample_table SET value = 30.5 WHERE name = 'beta'"
    )
    println "Updated $updated row(s)"
} 