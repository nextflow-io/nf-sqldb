#!/usr/bin/env nextflow

/*
 * Example script demonstrating how to use the SQL sqlExecute function
 */

include { sqlExecute } from 'plugin/nf-sqldb'
include { fromQuery } from 'plugin/nf-sqldb'

// Define database configuration in nextflow.config file
// sql.db.demo = [url: 'jdbc:h2:mem:demo', driver: 'org.h2.Driver']

workflow {
    log.info """
    =========================================
    SQL Execution Function Example
    =========================================
    """

    // Step 1: Create a table (DDL operation returns null)
    log.info "Creating a sample table..."
    def createResult = sqlExecute(
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

    // Step 2: Insert some data (DML operation returns affected row count)
    log.info "Inserting data..."
    def insertCount = sqlExecute(
        db: 'demo',
        statement: '''
            INSERT INTO TEST_TABLE (ID, NAME, VALUE) VALUES
            (1, 'alpha', 10.5),
            (2, 'beta', 20.7),
            (3, 'gamma', 30.2),
            (4, 'delta', 40.9);
        '''
    )
    log.info "Inserted $insertCount rows"

    // Step 3: Update some data (DML operation returns affected row count)
    log.info "Updating data..."
    def updateCount = sqlExecute(
        db: 'demo',
        statement: '''
            UPDATE TEST_TABLE
            SET VALUE = VALUE * 2
            WHERE ID = 2;
        '''
    )
    log.info "Updated $updateCount rows"

    // Step 4: Delete some data (DML operation returns affected row count)
    log.info "Deleting data..."
    def deleteCount = sqlExecute(
        db: 'demo',
        statement: '''
            DELETE FROM TEST_TABLE
            WHERE ID = 4;
        '''
    )
    log.info "Deleted $deleteCount rows"

    // Step 5: Query results
    log.info "Querying results..."
    channel
        .fromQuery("SELECT * FROM TEST_TABLE ORDER BY ID", db: 'demo')
        .view { row -> "ID: ${row[0]}, Name: ${row[1]}, Value: ${row[2]}" }
} 