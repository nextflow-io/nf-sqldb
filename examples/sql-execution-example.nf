#!/usr/bin/env nextflow

/*
 * Example script demonstrating how to use the SQL execute and executeUpdate functions
 */

include { execute; executeUpdate } from 'plugin/nf-sqldb'
include { fromQuery } from 'plugin/nf-sqldb'

// Define database configuration in nextflow.config file
// sql.db.demo = [url: 'jdbc:h2:mem:demo', driver: 'org.h2.Driver']

workflow {
    log.info """
    =========================================
    SQL Execution Functions Example
    =========================================
    """

    // Step 1: Create a table
    log.info "Creating a sample table..."
    execute(
        db: 'demo',
        statement: '''
            CREATE TABLE IF NOT EXISTS test_table (
                id INTEGER PRIMARY KEY,
                name VARCHAR(100),
                value DOUBLE
            )
        '''
    )

    // Step 2: Insert some data
    log.info "Inserting data..."
    execute(
        db: 'demo',
        statement: '''
            INSERT INTO test_table (id, name, value) VALUES
            (1, 'alpha', 10.5),
            (2, 'beta', 20.7),
            (3, 'gamma', 30.2),
            (4, 'delta', 40.9)
        '''
    )

    // Step 3: Update data and get affected row count
    log.info "Updating data..."
    def updatedRows = executeUpdate(
        db: 'demo',
        statement: "UPDATE test_table SET value = value * 2 WHERE value > 20"
    )
    log.info "Updated $updatedRows row(s)"

    // Step 4: Delete data and get affected row count
    log.info "Deleting data..."
    def deletedRows = executeUpdate(
        db: 'demo',
        statement: "DELETE FROM test_table WHERE value > 60"
    )
    log.info "Deleted $deletedRows row(s)"

    // Step 5: Query the results to verify
    log.info "Querying remaining data..."
    channel
        .fromQuery("SELECT * FROM test_table ORDER BY id", db: 'demo')
        .view { row -> "ID: ${row[0]}, Name: ${row[1]}, Value: ${row[2]}" }
} 