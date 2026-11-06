# Reference

This plugin provides the `sqlQuery` function for querying data, and the `sqlExecute` function for executing SQL statements that don't return data, such as DDL (Data Definition Language) and DML (Data Manipulation Language) operations. It also provides the `fromQuery` channel factory and the `sqlInsert` operator, which are deprecated because plugin channel factories and operators are not supported with static typing.

_NOTE:_ `fromQuery` and `sqlInsert` are deprecated. Use `sqlQuery` and `sqlExecute` instead (see [Migrating from fromQuery](#migrating-from-fromquery) and [Migrating from sqlInsert](#migrating-from-sqlinsert)).

## sqlQuery

The `sqlQuery` function queries a SQL database and returns a list of [records](https://docs.seqera.io/nextflow/reference/stdlib-types/record), one for each row in the result set. For example:

```nextflow
include { sqlQuery } from 'plugin/nf-sqldb'

def rows = sqlQuery(db: 'foo', query: 'SELECT id, name FROM sample ORDER BY id')
println rows // [[id: 1, name: 'alpha'], [id: 2, name: 'beta']]
```

Use `channel.fromList` to emit the rows in a channel:

```nextflow
channel.fromList(sqlQuery(db: 'foo', query: 'SELECT id, name FROM sample'))
    .view { row -> "${row.id}: ${row.name}" }
```

Use `params` to pass values to the query as bind parameters, instead of formatting them into the SQL string. Use a list for positional `?` placeholders, or a map for named `:name` placeholders:

```nextflow
sqlQuery(db: 'foo', query: 'SELECT id, name FROM sample WHERE value > ?', params: [10])

sqlQuery(db: 'foo', query: 'SELECT id, name FROM sample WHERE name = :name', params: [name: 'alpha'])
```

Since `sqlQuery` is a function, it can also be called in an operator closure, for example to look up each item of a channel and emit the matching rows:

```nextflow
channel.of('alpha', 'beta')
    .flatMap { name -> sqlQuery(db: 'foo', query: 'SELECT name, value FROM sample WHERE name = ?', params: [name]) }
    .view()
```

Consecutive calls reuse the same connection to the database (see [Connections](#connections)).

`sqlQuery` runs as soon as it is called, so it sees the changes made by any `sqlExecute` call before it:

```nextflow
include { sqlExecute; sqlQuery } from 'plugin/nf-sqldb'

workflow {
    sqlExecute(db: 'foo', statement: "INSERT INTO sample (id, name) VALUES (3, 'gamma')")
    channel.fromList(sqlQuery(db: 'foo', query: 'SELECT id, name FROM sample')).view()
}
```

To query the database after inserting the items of a channel, query it in the same closure:

```nextflow
ch.collect().subscribe { rows ->
    def res = sqlExecute(db: 'foo', statement: 'INSERT INTO sample (id, name) VALUES (?, ?)', batch: rows.collect { r -> [r.id, r.name] })
    if( !res.success )
        error(res.error)
    println sqlQuery(db: 'foo', query: 'SELECT count(*) AS total FROM sample')
}
```

The following options are available:

`db`
: The database handle. It must be defined under `sql.db` in the Nextflow configuration. If not specified, the `default` database is used (see [Configuration](configuration.md#default-database)).

`query`
: The SQL query to execute (required).

`params`
: The bind parameters for the query: a list of values for positional `?` placeholders, or a map of values for named `:name` placeholders. Named placeholders are parsed in the same way as for [sqlExecute](#sqlexecute).

### Return value

The result is a list with a record for each row, in the order returned by the database. Each record maps the column labels (the column alias if specified, otherwise the column name) to the row values, in column order. Records are immutable, and their keys are case-sensitive.

If the database stores unquoted identifiers in upper case (e.g. H2 and Oracle), column labels that are entirely in upper case are converted to lower case. Otherwise, column labels are used as reported by the database. As a result, unquoted column names are lower case on most databases, while quoted aliases keep their case. For example, `SELECT id, name AS "FullName" FROM sample` returns records with the keys `id` and `FullName` on H2, Postgres, and MySQL.

Column labels must be unique: a query whose result has two columns with the same label, such as `id` from two joined tables, is rejected. Use column aliases to make them unique.

The values are returned as provided by the database driver, e.g. `Integer`, `BigDecimal`, `String`, or `java.sql.Timestamp`, except for values that can't be read after the connection is closed, which are converted as follows: character large objects (`CLOB`) and XML values to `String`, binary large objects (`BLOB`) to `byte[]`, and arrays to `List` (nested arrays to nested lists). Other array values returned by the driver (except `byte[]`) are also converted to `List`.

The whole result set is loaded into memory. Use a `LIMIT` clause or more selective queries for large tables.

### Errors

Unlike `sqlExecute`, `sqlQuery` throws an error on failure, which stops the run:

- Invalid options (e.g. a missing `query`) or an unknown database throw an `IllegalArgumentException`
- A database error (e.g. invalid SQL or a connection failure) throws an `IllegalStateException`, with a message including the database name and the database error

## fromQuery

_DEPRECATED:_ plugin channel factories are not supported with static typing. Use `sqlQuery` instead (see [Migrating from fromQuery](#migrating-from-fromquery)).

The `fromQuery` factory method queries a SQL database and creates a channel that emits a tuple for each row in the corresponding result set. For example:

```nextflow
include { fromQuery } from 'plugin/nf-sqldb'

channel.fromQuery('select alpha, delta, omega from SAMPLE', db: 'foo').view()
```

The following options are available:

`db`
: The database handle. It must be defined under `sql.db` in the Nextflow configuration. If not specified, the `default` database is used (see [Configuration](configuration.md#default-database)).

`batchSize`
: Query the data in batches of the given size. This option is recommended for queries that may return a large result set, so that the entire result set is not loaded into memory at once.
: _NOTE:_ this feature requires that the underlying SQL database supports `LIMIT` and `OFFSET`. Queries that already contain a `LIMIT` or `OFFSET` clause are rejected when this option is specified.

`batchDelay`
: The delay in milliseconds between consecutive batches when `batchSize` is specified (default: `100`).

`emitColumns`
: When `true`, the column names in the `SELECT` statement are emitted as the first tuple in the resulting channel.

### Migrating from fromQuery

Use `sqlQuery` with `channel.fromList` in place of `fromQuery`:

```nextflow
// before
channel.fromQuery('SELECT alpha, delta FROM sample', db: 'foo')

// after
channel.fromList(sqlQuery(db: 'foo', query: 'SELECT alpha, delta FROM sample'))
```

Note the following differences:

- `sqlQuery` emits a record for each row instead of a tuple, so use the column names instead of indices, e.g. `row.alpha` instead of `row[0]`. Use `.map { row -> tuple(row.alpha, row.delta) }` to get the same tuples as `fromQuery`. See [Return value](#return-value) for the case of the keys.
- `sqlQuery` reads the whole result set into memory before returning, while `fromQuery` with `batchSize` reads it in batches. There is no equivalent of `batchSize` and `batchDelay`.
- There is no equivalent of `emitColumns`, as each record carries the column names.
- `sqlQuery` runs when it is called, while `fromQuery` runs when the workflow starts.
- `sqlQuery` throws an error on failure.

## sqlInsert

_DEPRECATED:_ plugin operators are not supported with static typing. Use `sqlExecute` with the `params` or `batch` option instead (see [Migrating from sqlInsert](#migrating-from-sqlinsert)).

The `sqlInsert` operator inserts each item from a source channel into a SQL database table, and emits the item unchanged. For example:

```nextflow
include { sqlInsert } from 'plugin/nf-sqldb'

channel
    .of('Hello','world!')
    .map { it -> tuple(it, it.size()) }
    .sqlInsert( into: 'SAMPLE', columns: 'NAME, LEN', db: 'foo' )
```

The above example executes the following SQL statements into the database `foo` (as defined in the Nextflow configuration).

```sql
INSERT INTO SAMPLE (NAME, LEN) VALUES ('Hello', 5);
INSERT INTO SAMPLE (NAME, LEN) VALUES ('world!', 6);
```

_NOTE:_ the target table (e.g. `SAMPLE` in the above example) must be created beforehand.

Each channel item can be a tuple (list), a map, or a single value (treated as a one-element tuple). For map items, each value is inserted into the column matching its key. For example:

```nextflow
channel
    .of( [name: 'Hello', len: 5], [name: 'world!', len: 6] )
    .sqlInsert( into: 'SAMPLE', db: 'foo' )
```

The following options are available:

`db`
: The database handle. It must be defined under `sql.db` in the Nextflow configuration. If not specified, the `default` database is used (see [Configuration](configuration.md#default-database)).

`into`
: The target table for inserting the data, in the form `[[catalog.]schema.]table`, e.g. `myschema.mytable`. On MySQL, a single prefix is the database, e.g. `mydb.mytable`. Quoted identifiers and names containing dots are not supported. When the columns are inferred, each part of the name is matched as written or in the database's default identifier case (but not with a different case for each part).

`columns`
: The database table column names to be filled with the channel data. The column names order and cardinality must match the tuple values emitted by the channel. The columns can be specified as a list or as a string of comma-separated values.
: When not specified, the columns are inferred from the first item: for tuples, all columns of the target table are used, in table order (from the table metadata); for maps, the table columns matching the map keys are used. Map keys are matched to column names ignoring case.

`statement`
: The SQL `INSERT` statement to execute, using `?` as a placeholder for the actual values, for example: `insert into SAMPLE(X,Y) values (?,?)`. The `into` and `columns` options are ignored when this option is provided.

`batchSize`
: Insert the data in batches of the given size (default: `10`).

`setup`
: A SQL statement that is executed before inserting the data, e.g. to create the target table.
: _NOTE:_ the underlying database should support the _create table if not exist_ idiom, as the plugin will execute this statement every time the script is run.

_NOTE:_ Like all dataflow operators in Nextflow, the operators provided by this plugin are executed asynchronously. In particular, data inserted using the `sqlInsert` operator is _not_ guaranteed to be available to any subsequent queries using the `fromQuery` operator, as it is not possible to make a channel factory operation dependent on some upstream operation.

### Migrating from sqlInsert

Use `sqlExecute` with bind parameters in place of `sqlInsert`. Unlike `sqlInsert`, `sqlExecute` doesn't throw on failure: it logs the error and returns `[success: false, error: ...]`, so check the result as shown below if a failure should stop the run. To insert each item as it is emitted, call `sqlExecute` with `params` in `subscribe`:

```nextflow
include { sqlExecute } from 'plugin/nf-sqldb'

ch.subscribe { r ->
    def res = sqlExecute(db: 'foo', statement: 'INSERT INTO SAMPLE (NAME, LEN) VALUES (?, ?)', params: [r.name, r.len])
    if( !res.success )
        error(res.error)
}
```

This commits a transaction for each item, which can be slow for many items. Consecutive calls reuse the same connection to the database (see [Connections](#connections)).

To insert all items at once, collect them and call `sqlExecute` with `batch`:

```nextflow
ch.collect().subscribe { rows ->
    def res = sqlExecute(db: 'foo', statement: 'INSERT INTO SAMPLE (NAME, LEN) VALUES (?, ?)', batch: rows.collect { r -> [r.name, r.len] })
    if( !res.success )
        error(res.error)
}
```

This inserts the rows in a single transaction (if the database driver supports transactions), so either all of them or none are inserted, but it waits for the channel to complete and holds all of the rows in memory.

Unlike `sqlInsert`, `sqlExecute` doesn't infer the columns or create the statement from a table name, and doesn't throw an error on failure. Check the `success` key of the result to detect failures. Use `sqlExecute` without parameters in place of the `setup` option.

## sqlExecute

The `sqlExecute` function executes a SQL statement that doesn't return a result set, such as `CREATE`, `ALTER`, `DROP`, `INSERT`, `UPDATE`, or `DELETE` statements. Use it for database setup, administrative commands, and other one-off operations whose results you don't need as part of your dataflow. It returns a map with the following keys:

- For DDL statements (`CREATE`, `ALTER`, `DROP`, `TRUNCATE`): `[success: true, result: null]`
- For any other statement, such as DML (`INSERT`, `UPDATE`, `DELETE`): `[success: true, result: <number of affected rows>]`
- On failure (e.g. invalid SQL or unknown database): `[success: false, error: <error message>]`. Errors are logged but not thrown, so check `success` to detect failures.

For example:

```nextflow
include { sqlExecute } from 'plugin/nf-sqldb'

def createResult = sqlExecute(
    db: 'foo',
    statement: '''
        CREATE TABLE IF NOT EXISTS sample_table (
            id INTEGER PRIMARY KEY,
            name VARCHAR(100),
            value DOUBLE
        )
    '''
)
println "Create result: $createResult" // [success: true, result: null]

def insertResult = sqlExecute(
    db: 'foo',
    statement: "INSERT INTO sample_table (id, name, value) VALUES (1, 'alpha', 10.5)"
)
println "Inserted ${insertResult.result} row(s)" // [success: true, result: 1]

def updateResult = sqlExecute(
    db: 'foo',
    statement: "UPDATE sample_table SET value = 30.5 WHERE name = 'alpha'"
)
println "Updated ${updateResult.result} row(s)" // [success: true, result: <number>]

def deleteResult = sqlExecute(
    db: 'foo',
    statement: "DELETE FROM sample_table WHERE value > 25"
)
println "Deleted ${deleteResult.result} row(s)" // [success: true, result: <number>]
```

Use `params` to pass values to the statement as bind parameters, instead of formatting them into the SQL string. The database driver handles quoting and `null` values, which protects against SQL injection. Use a list for positional `?` placeholders, or a map for named `:name` placeholders:

```nextflow
sqlExecute(
    db: 'foo',
    statement: 'UPDATE sample_table SET name = ? WHERE name = ?',
    params: ["O'Brien", 'alpha']
)

sqlExecute(
    db: 'foo',
    statement: 'INSERT INTO sample_table (id, name, value) VALUES (:id, :name, :value)',
    params: [id: 2, name: 'delta', value: null]
)
```

With static typing, the values in a list must have the same type, so use a map for values of different types.

Use `batch` to execute a statement for many sets of parameters at once:

```nextflow
def result = sqlExecute(
    db: 'foo',
    statement: 'INSERT INTO sample_table (id, name, value) VALUES (:id, :name, :value)',
    batch: [
        [id: 3, name: 'beta', value: 1.5],
        [id: 4, name: 'gamma', value: 2.5]
    ]
)
println "Inserted ${result.result} row(s)" // [success: true, result: 2]
```

The following options are available:

`db`
: The database handle. It must be defined under `sql.db` in the Nextflow configuration. If not specified, the `default` database is used (see [Configuration](configuration.md#default-database)).

`statement`
: The SQL statement to execute. This can be any DDL or DML statement that doesn't return a result set.

`params`
: The bind parameters for the statement: a list of values for positional `?` placeholders, or a map of values for named `:name` placeholders. Named placeholders inside single-quoted string literals and comments are ignored. Use positional params if the statement contains a double-quoted or backtick identifier with a `:`, a dollar-quoted string, or a `?`-based operator (e.g. the Postgres jsonb `?|` operator, written as `??|` with the Postgres JDBC driver), as named placeholders may not be parsed correctly. Each named placeholder must have a value in the map, while extra keys are ignored. A statement can't mix positional and named placeholders when using a map.

`batch`
: A list of bind parameters, each a list or a map as for `params` (all of the same kind). The statement is executed once for each item as a JDBC batch, in a single transaction if the database driver supports transactions: if any item fails, the transaction is rolled back and none of the changes are applied. Otherwise (e.g. Databricks), the batch is executed without a transaction and is not atomic: items executed before a failure remain applied. The result is the total number of affected rows, not counting items for which the database driver doesn't report a count. Can't be used together with `params`.

## Connections

The `sqlQuery` and `sqlExecute` functions keep the connection to each database open after a call, so that the next call to the same database can reuse it instead of opening a new connection, until the end of the run. Calls that run at the same time (e.g. from different threads) open separate connections, as needed. Only one connection is kept per database, and the others are closed when their call is done. A connection that has been idle for more than 30 seconds is checked before it is reused, and replaced if it was closed by the server.

A connection is closed instead of kept if the call fails, or if it leaves autocommit disabled. A transaction started with SQL statements (e.g. `BEGIN`) isn't detected by most drivers (e.g. PostgreSQL), so commit or roll back such a transaction in the same call. Other session state set with SQL statements (e.g. `SET` statements or temporary tables) may carry over to later calls, so don't rely on it either way.

Connections to embedded databases stored in a file (e.g. DuckDB, SQLite or H2 file databases) are not kept, so that the file isn't locked between calls. Neither are connections to private in-memory databases, which are created for each connection (e.g. `jdbc:duckdb:`, `jdbc:sqlite::memory:` or an unnamed `jdbc:h2:mem:`), so each call gets a new empty database. Use a named in-memory database (e.g. `jdbc:h2:mem:mydb;DB_CLOSE_DELAY=-1`) or a file database to share data across calls.

The `fromQuery` factory and `sqlInsert` operator don't reuse connections: each opens its own connection, which is used for the whole query or channel.
