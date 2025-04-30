# SQL DB plugin for Nextflow

This plugin provides support for interacting with SQL databases in Nextflow scripts.

The following databases are currently supported:

* [AWS Athena](https://aws.amazon.com/athena/) (Setup guide [here](docs/aws-athena.md))
* [DuckDB](https://duckdb.org/)
* [H2](https://www.h2database.com)
* [MySQL](https://www.mysql.com/)
* [MariaDB](https://mariadb.org/)
* [PostgreSQL](https://www.postgresql.org/)
* [SQLite](https://www.sqlite.org/index.html)

NOTE: THIS IS A PREVIEW TECHNOLOGY, FEATURES AND CONFIGURATION SETTINGS CAN CHANGE IN FUTURE RELEASES.

## Getting started

This plugin requires Nextflow `22.08.1-edge` or later. You can enable the plugin by adding the following snippet to your `nextflow.config` file:

```groovy
plugins {
    id 'nf-sqldb'
}
```


## Configuration

You can configure any number of databases under the `sql.db` configuration scope. For example:

```groovy
sql {
    db {
        foo {
            url = 'jdbc:mysql://localhost:3306/demo'
            user = 'my-user'
            password = 'my-password'
        }
    }
}
```

The above example defines a database named `foo` that connects to a MySQL server running locally at port 3306 and
using the `demo` schema, with `my-name` and `my-password` as credentials.

The following options are available:

`sql.db.'<DB-NAME>'.url`
: The database connection URL based on the [JDBC standard](https://docs.oracle.com/javase/tutorial/jdbc/basics/connecting.html#db_connection_url).

`sql.db.'<DB-NAME>'.driver`
: The database driver class name (optional).

`sql.db.'<DB-NAME>'.user`
: The database connection user name.

`sql.db.'<DB-NAME>'.password`
: The database connection password.

## Dataflow Operators

This plugin provides the following dataflow operators for querying from and inserting into database tables.

### fromQuery

The `fromQuery` factory method queries a SQL database and creates a channel that emits a tuple for each row in the corresponding result set. For example:

```nextflow
include { fromQuery } from 'plugin/nf-sqldb'

channel.fromQuery('select alpha, delta, omega from SAMPLE', db: 'foo').view()
```

The following options are available:

`db`
: The database handle. It must be defined under `sql.db` in the Nextflow configuration.

`batchSize`
: Query the data in batches of the given size. This option is recommended for queries that may return large a large result set, so that the entire result set is not loaded into memory at once.
: *NOTE:* this feature requires that the underlying SQL database supports `LIMIT` and `OFFSET`.

`emitColumns`
: When `true`, the column names in the `SELECT` statement are emitted as the first tuple in the resulting channel.

### sqlInsert

The `sqlInsert` operator collects the items in a source channel and inserts them into a SQL database. For example:

```nextflow
include { sqlInsert } from 'plugin/nf-sqldb'

channel
    .of('Hello','world!')
    .map( it -> tuple(it, it.length) )
    .sqlInsert( into: 'SAMPLE', columns: 'NAME, LEN', db: 'foo' )
```

The above example executes the following SQL statements into the database `foo` (as defined in the Nextflow configuration).

```sql
INSERT INTO SAMPLE (NAME, LEN) VALUES ('HELLO', 5);
INSERT INTO SAMPLE (NAME, LEN) VALUES ('WORLD!', 6);
```

*NOTE:* the target table (e.g. `SAMPLE` in the above example) must be created beforehand.

The following options are available:

`db`
: The database handle. It must be defined under `sql.db` in the Nextflow configuration.

`into`
: The target table for inserting the data.

`columns`
: The database table column names to be filled with the channel data. The column names order and cardinality must match the tuple values emitted by the channel. The columns can be specified as a list or as a string of comma-separated values.

`statement`
: The SQL `INSERT` statement to execute, using `?` as a placeholder for the actual values, for example: `insert into SAMPLE(X,Y) values (?,?)`. The `into` and `columns` options are ignored when this option is provided.

`batchSize`
: Insert the data in batches of the given size (default: `10`).

`setup`
: A SQL statement that is executed before inserting the data, e.g. to create the target table.
: *NOTE:* the underlying database should support the *create table if not exist* idiom, as the plugin will execute this statement every time the script is run.

## SQL Execution Functions

This plugin provides the following functions for executing SQL statements that don't return data, such as DDL (Data Definition Language) and DML (Data Manipulation Language) operations.

### execute

The `execute` function executes a SQL statement that doesn't return a result set, such as `CREATE`, `ALTER`, `DROP`, `INSERT`, `UPDATE`, or `DELETE` statements. For example:

```nextflow
include { execute } from 'plugin/nf-sqldb'

// Create a table
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

// Insert data
execute(
    db: 'foo',
    statement: "INSERT INTO sample_table (id, name, value) VALUES (1, 'alpha', 10.5)"
)

// Delete data
execute(
    db: 'foo',
    statement: "DELETE FROM sample_table WHERE id = 1"
)
```

The following options are available:

`db`
: The database handle. It must be defined under `sql.db` in the Nextflow configuration.

`statement`
: The SQL statement to execute. This can be any DDL or DML statement that doesn't return a result set.

### executeUpdate

The `executeUpdate` function is similar to `execute`, but it returns the number of rows affected by the SQL statement. This is particularly useful for DML operations like `INSERT`, `UPDATE`, and `DELETE` where you need to know how many rows were affected. For example:

```nextflow
include { executeUpdate } from 'plugin/nf-sqldb'

// Insert data and get the number of rows inserted
def insertedRows = executeUpdate(
    db: 'foo',
    statement: "INSERT INTO sample_table (id, name, value) VALUES (2, 'beta', 20.5)"
)
println "Inserted $insertedRows row(s)"

// Update data and get the number of rows updated
def updatedRows = executeUpdate(
    db: 'foo',
    statement: "UPDATE sample_table SET value = 30.5 WHERE name = 'beta'"
)
println "Updated $updatedRows row(s)"

// Delete data and get the number of rows deleted
def deletedRows = executeUpdate(
    db: 'foo',
    statement: "DELETE FROM sample_table WHERE value > 25"
)
println "Deleted $deletedRows row(s)"
```

The following options are available:

`db`
: The database handle. It must be defined under `sql.db` in the Nextflow configuration.

`statement`
: The SQL statement to execute. This should be a DML statement that can return a count of affected rows.

## Differences Between Dataflow Operators and Execution Functions

The plugin provides two different ways to interact with databases:

1. **Dataflow Operators** (`fromQuery` and `sqlInsert`): These are designed to integrate with Nextflow's dataflow programming model, operating on channels.
   - `fromQuery`: Queries data from a database and returns a channel that emits the results.
   - `sqlInsert`: Takes data from a channel and inserts it into a database.

2. **Execution Functions** (`execute` and `executeUpdate`): These are designed for direct SQL statement execution that doesn't require channel integration.
   - `execute`: Executes a SQL statement without returning any data.
   - `executeUpdate`: Executes a SQL statement and returns the count of affected rows.

Use **Dataflow Operators** when you need to:
- Query data that will flow into your pipeline processing
- Insert data from your pipeline processing into a database

Use **Execution Functions** when you need to:
- Perform database setup (creating tables, schemas, etc.)
- Execute administrative commands
- Perform one-off operations (deleting all records, truncating a table, etc.)
- Execute statements where you don't need the results as part of your dataflow

## Querying CSV files

This plugin supports the [H2](https://www.h2database.com/html/main.html) database engine, which can query CSV files like database tables using SQL statements.

For example, create a CSV file using the snippet below:

```bash
cat <<EOF > test.csv
foo,bar
1,hello
2,ciao
3,hola
4,bonjour
EOF
```

Then query it in a Nextflow script:

```nextflow
include { fromQuery } from 'plugin/nf-sqldb'

channel
    .fromQuery("SELECT * FROM CSVREAD('test.csv') where foo>=2;")
    .view()
```

The `CSVREAD` function provided by the H2 database engine allows you to query any CSV file in your filesystem. As shown in the example, you can use standard SQL clauses like `SELECT` and `WHERE` to define your query.

## Caveats

Like all dataflow operators in Nextflow, the operators provided by this plugin are executed asynchronously.

In particular, data inserted using the `sqlInsert` operator is *not* guaranteed to be available to any subsequent queries using the `fromQuery` operator, as it is not possible to make a channel factory operation dependent on some upstream operation.
