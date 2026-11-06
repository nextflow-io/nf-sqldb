# SQL DB plugin for Nextflow

This plugin provides support for interacting with SQL databases in Nextflow scripts.

The following databases are currently supported:

- [AWS Athena](https://aws.amazon.com/athena/) (Setup guide [here](docs/aws-athena.md))
- [DuckDB](https://duckdb.org/)
- [H2](https://www.h2database.com)
- [MySQL](https://www.mysql.com/)
- [MariaDB](https://mariadb.org/)
- [PostgreSQL](https://www.postgresql.org/)
- [SQLite](https://www.sqlite.org/index.html)

## Getting started

This plugin requires Nextflow `25.10.0` or later. You can enable the plugin by adding the following snippet to your `nextflow.config` file:

```groovy
plugins {
    id 'nf-sqldb'
}
```

Then define one or more databases under the `sql.db` configuration scope:

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

## Usage

The `fromQuery` channel factory and the `sqlInsert` operator move data between a database and a channel, while the `sqlExecute` function runs a single statement directly, such as creating a table, outside of the dataflow.

Use `fromQuery` to query a database and emit a tuple for each row:

```nextflow
include { fromQuery } from 'plugin/nf-sqldb'

channel.fromQuery('select alpha, delta, omega from SAMPLE', db: 'foo').view()
```

Use `sqlInsert` to insert each item of a channel into a table:

```nextflow
include { sqlInsert } from 'plugin/nf-sqldb'

channel
    .of('Hello','world!')
    .map { it -> tuple(it, it.size()) }
    .sqlInsert( into: 'SAMPLE', columns: 'NAME, LEN', db: 'foo' )
```

Use `sqlExecute` to run a statement that doesn't return a result set. It returns a map with `success` and either `result` or `error`:

```nextflow
include { sqlExecute } from 'plugin/nf-sqldb'

sqlExecute(db: 'foo', statement: 'CREATE TABLE IF NOT EXISTS sample_table (id INTEGER, name VARCHAR(100))')
// [success: true, result: null]

sqlExecute(db: 'foo', statement: "INSERT INTO sample_table (id, name) VALUES (1, 'alpha')")
// [success: true, result: 1]
```

## Documentation

- [Configuration](docs/configuration.md): database options and the `default` database
- [Reference](docs/reference.md): all options for `fromQuery`, `sqlInsert`, and `sqlExecute`
- [Secrets](docs/secrets.md): using Nextflow secrets for database credentials
- [AWS Athena](docs/aws-athena.md): querying AWS Athena
- [Querying CSV files](docs/h2-csv.md): querying CSV files with H2

See also the [examples](examples/), the [changelog](CHANGELOG.md), and the [contributing guide](CONTRIBUTING.md).
