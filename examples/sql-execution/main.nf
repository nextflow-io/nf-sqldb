#!/usr/bin/env nextflow

/*
 * Example script demonstrating how to use the SQL sqlExecute and executeUpdate functions
 */

include { sqlExecute; executeUpdate } from 'plugin/nf-sqldb'
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
    def createResult = executeUpdate(
        db: 'demo',
        statement: '''
            CREATE TABLE IF NOT EXISTS TEST_TABLE (
                ID INTEGER PRIMARY KEY,
                NAME VARCHAR(100),
                VALUE DOUBLE
            )
        '''
    )
    log.info "Create table result: $createResult"

    // Step 2: Insert some data
    log.info "Inserting data..."
    executeUpdate(
        db: 'demo',
        statement: '''
            INSERT INTO TEST_TABLE (ID, NAME, VALUE) VALUES
            (1, 'alpha', 10.5),
            (2, 'beta', 20.7),
            (3, 'gamma', 30.2),
            (4, 'delta', 40.9);
        '''
    )

    // Step 3: Update some data
    log.info "Updating data..."
    executeUpdate(
        db: 'demo',
        statement: '''
            UPDATE TEST_TABLE
            SET VALUE = VALUE * 2
            WHERE ID = 2;
        '''
    )

    // Step 4: Delete some data
    log.info "Deleting data..."
    executeUpdate(
        db: 'demo',
        statement: '''
            DELETE FROM TEST_TABLE
            WHERE ID = 4;
        '''
    )

    // Step 5: Query results
    log.info "Querying results..."
    channel
        .fromQuery("SELECT * FROM TEST_TABLE ORDER BY ID", db: 'demo')
        .view { row -> "ID: ${row[0]}, Name: ${row[1]}, Value: ${row[2]}" }
} 