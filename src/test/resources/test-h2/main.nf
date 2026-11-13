#!/usr/bin/env nextflow

/*
 * Example script demonstrating how to use the SQL sqlExecute function
 */

include { sqlExecute; sqlQuery } from 'plugin/nf-sqldb'

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
        .fromList(sqlQuery(db: 'demo', query: 'SELECT * FROM TEST_TABLE ORDER BY ID'))
        .view { row -> "ID: ${row.id}, Name: ${row.name}, Value: ${row.value}" }
}

workflow example {
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
    if (!createResult.success)
        error("Failed to create table: ${createResult.error}")

    // Insert data using sqlExecute with a batch of bind parameters
    sqlExecute(
        db: 'foo',
        statement: 'INSERT INTO sample_table (id, name, value) VALUES (?, ?, ?)',
        batch: [[1, 'alpha', 10.5], [2, 'beta', 20.5]]
    )

    // Query data using sqlQuery
    channel.fromList(sqlQuery(db: 'foo', query: 'SELECT * FROM sample_table'))
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
