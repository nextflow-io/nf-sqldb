#!/usr/bin/env nextflow

/*
 * Example script demonstrating how to use the SQL sqlExecute function
 */

include { fromQuery; sqlExecute } from 'plugin/nf-sqldb'

// The `demo` database is defined in nextflow.config:
// sql { db { demo { url = 'jdbc:h2:./demo' } } }

workflow {
    log.info """
    =========================================
    SQL Execution Function Example
    =========================================
    """

    // Step 1: Create a table (DDL operation returns a null result)
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
    def insertResult = sqlExecute(
        db: 'demo',
        statement: '''
            INSERT INTO TEST_TABLE (ID, NAME, VALUE) VALUES
            (1, 'alpha', 10.5),
            (2, 'beta', 20.7),
            (3, 'gamma', 30.2),
            (4, 'delta', 40.9);
        '''
    )
    log.info "Inserted ${insertResult.result} rows"

    // Step 3: Update some data (DML operation returns affected row count)
    log.info "Updating data..."
    def updateResult = sqlExecute(
        db: 'demo',
        statement: '''
            UPDATE TEST_TABLE
            SET VALUE = VALUE * 2
            WHERE ID = 2;
        '''
    )
    log.info "Updated ${updateResult.result} rows"

    // Step 4: Delete some data (DML operation returns affected row count)
    log.info "Deleting data..."
    def deleteResult = sqlExecute(
        db: 'demo',
        statement: '''
            DELETE FROM TEST_TABLE
            WHERE ID = 4;
        '''
    )
    log.info "Deleted ${deleteResult.result} rows"

    // Step 5: Query results
    log.info "Querying results..."
    channel
        .fromQuery("SELECT * FROM TEST_TABLE ORDER BY ID", db: 'demo')
        .view { row -> "ID: ${row[0]}, Name: ${row[1]}, Value: ${row[2]}" }
} 