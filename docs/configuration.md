# Configuration

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
using the `demo` schema, with `my-user` and `my-password` as credentials.

## Options

The following options are available:

`sql.db.'<DB-NAME>'.url`
: The database connection URL based on the [JDBC standard](https://docs.oracle.com/javase/tutorial/jdbc/basics/connecting.html#db_connection_url).

`sql.db.'<DB-NAME>'.driver`
: The database driver class name (optional). If not specified, the driver is inferred from the JDBC URL for the following databases: AWS Athena (`jdbc:awsathena:`), DuckDB (`jdbc:duckdb:`), H2 (`jdbc:h2:`), MariaDB (`jdbc:mariadb:`), MySQL (`jdbc:mysql:`), PostgreSQL (`jdbc:postgresql:`), and SQLite (`jdbc:sqlite:`).

`sql.db.'<DB-NAME>'.user`
: The database connection user name.

`sql.db.'<DB-NAME>'.password`
: The database connection password.

## Default database

The `default` database is used when an operator or function does not specify a database. Unless configured, it is an in-memory H2 database (`jdbc:h2:mem:`) with user `sa`. Settings defined under `sql.db.default` also act as fallback values for all other databases.

## Secrets

For information on using secrets with database credentials, see [secrets.md](secrets.md).
