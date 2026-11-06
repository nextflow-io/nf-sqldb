#!/usr/bin/env nextflow

/*
 * Example script demonstrating how to use the sqlExecute and sqlQuery functions
 */

nextflow.enable.types = true

include { sqlExecute; sqlQuery } from 'plugin/nf-sqldb'

workflow {
    // Create a table (DDL statements return a null result)
    sqlExecute(
        db: 'demo',
        statement: '''
            CREATE TABLE TEST_TABLE (
                ID INTEGER PRIMARY KEY,
                NAME VARCHAR(100),
                VALUE DOUBLE
            )
        '''
    )

    // Insert the items of a channel as a single batch
    channel.of(
            record(id: 1, name: 'alpha', value: 10.5),
            record(id: 2, name: 'beta', value: 20.7),
            record(id: 3, name: 'gamma', value: 30.2),
            record(id: 4, name: 'delta', value: 40.9)
        )
        .collect()
        .map { rows ->
            sqlExecute(
                db: 'demo',
                statement: 'INSERT INTO TEST_TABLE (ID, NAME, VALUE) VALUES (:id, :name, :value)',
                batch: rows
            )
        }
        .flatMap { inserted ->
            log.info "Inserted ${inserted.result} rows"

            // Update and delete rows (DML statements return the number of affected rows)
            def updated = sqlExecute(
                db: 'demo',
                statement: 'UPDATE TEST_TABLE SET VALUE = VALUE * 2 WHERE ID = :id',
                params: [id: 2]
            )
            log.info "Updated ${updated.result} rows"

            def deleted = sqlExecute(
                db: 'demo',
                statement: 'DELETE FROM TEST_TABLE WHERE ID = :id',
                params: [id: 4]
            )
            log.info "Deleted ${deleted.result} rows"

            // Query the table after the changes above
            sqlQuery(
                db: 'demo',
                query: 'SELECT * FROM TEST_TABLE WHERE VALUE > :min ORDER BY ID',
                params: [min: 0]
            )
        }
        .view { row -> "ID: ${row.id}, Name: ${row.name}, Value: ${row.value}" }
}
