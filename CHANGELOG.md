# Changelog

All notable changes to the nf-sqldb plugin will be documented here.

See [Keep a Changelog](https://keepachangelog.com/) for recommendations on how to structure this file.

## Unreleased

- Migrate build to the Nextflow Gradle plugin (#42)
- Require Nextflow 26.04.0 or later, for record support in `sqlQuery` (#43)
- Plugins that depend on nf-sqldb (via its Maven artifact) must also require Nextflow 26.04.0 or later when upgrading. Version 0.7.1 is the last release that supports older Nextflow versions (25.04 or later)
- Generate the plugin spec, including the `sql` config scope (#43)
- Remove DSL1 support (#41)
- Run integration tests against Postgres and MySQL (#11)
- `sqlInsert` now works with DuckDB, which supports batch inserts as of `duckdb_jdbc` 1.3.0.0 (#19)
- Fix `sqlInsert` with inferred columns to match table and column names ignoring case (e.g. Postgres and MySQL lower-case columns, `into: 'FOO'` on Postgres)
- Reject `sqlInsert` map records with keys that differ only by case
- Fix `sqlInsert` with inferred columns merging the columns of same-named tables in other schemas or MySQL databases; unqualified table names are looked up in the current schema first (#44)
- Support qualified `sqlInsert` table names (e.g. `into: 'myschema.mytable'`, `mydb.mytable` on MySQL, or an attached database on DuckDB) with inferred columns (#44)
- Fix `sqlInsert` failing on the first full batch with drivers that do not support transactions (e.g. Databricks)
- Fix `sqlInsert` with inferred columns treating `_` and `%` in table names as wildcards (#44)
- Fix `sqlInsert` with inferred columns failing with `SQLITE_BUSY` when several `sqlInsert` operators write to the same SQLite database (#21)
- Map record keys are now matched to the `columns` option ignoring case (previously a key such as `ID` for column `id` inserted NULL)
- Fix the built-in `default` H2 database losing its data between calls (e.g. a table created with `sqlExecute` was not visible to the next `sqlExecute`, `fromQuery` or `sqlInsert`). Built-in in-memory databases now keep their data for the duration of the run, and a database configured without `url` gets its own in-memory database instead of sharing the `default` one, unless `sql.db.default.url` is set (#50)
- Don't append a trailing semicolon to queries and statements, which Trino and other JDBC drivers reject (#16)
- Fix paginated queries (`batchSize`) with a trailing semicolon, newline or `--` comment
- Don't reject paginated queries whose identifiers merely contain `limit` or `offset` (e.g. `rate_limit`)
- Fix `sqlExecute` returning a row count instead of `null` for multi-line DDL statements
- Add the `params` option to `sqlExecute` for bind parameters, as a list for positional `?` placeholders or a map for named `:name` placeholders (#22)
- Add the `batch` option to `sqlExecute` to execute a statement for many sets of bind parameters in a single transaction
- Deprecate `sqlInsert`, which is not supported with static typing, in favor of `sqlExecute` with `params` or `batch`
- Add the `sqlQuery` function, which returns the rows of a query as a list of records and supports bind parameters and static typing (#22, #23)
- Deprecate `fromQuery`, which is not supported with static typing, in favor of `channel.fromList(sqlQuery(...))`
- Reuse the database connection across `sqlQuery` and `sqlExecute` calls, so that calling them for each item of a channel doesn't open a new connection for each call. One idle connection is kept per database (except embedded file and private in-memory databases) until the end of the run. Session state such as temporary tables or `SET` may carry over between calls

## [0.7.1] - 2025-08-21

- Fix unresolved secrets detection to prevent silent fallback to default credentials
- Add comprehensive secrets documentation and troubleshooting guide
- Improve error messages for unknown database configurations

## [0.7.0] - 2025-05-28

- Add `sqlExecute` and other minor improvements [072ae039]
- Bump postgresql:42.7.5
- Bump sqlite-jdbc:3.49.1.0
- Bump duckdb_jdbc:1.3.0.0

## [0.6.0] - 2025-05-23

- Update build scripts
- Remove nf-bigquery plugin
- Bump Nextflow to version 25.04.0
- Bump mysql-connector-java:8.0.33
- Bump mariadb-java-client:2.7.0
- Bump postgresql:42.7.4
- Bump sqlite-jdbc:3.47.0.0
- Bump duckdb_jdbc:0.10.2

## [0.5.0] - 2022-08-30

- Bump nextflow required version to 22.08.1-edge

## [0.4.1] - 2022-05-25

- Fix AWS Athena driver dependency

## [0.4.0] - 2022-04-09

- Add support for batched queries via `batchSize` attribute
- Add `emitColumns` option fromQuery operator

## [0.3.0] - 2022-02-06

- Add support for AWS Athena

## [0.2.0] - 2021-10-10

- Add support for batch inserts
- Add support for insert operation setup statement to create missing table(s)
- Add support for SQLite
- Add support for DuckDB

## [0.1.0] - 2021-09-01

- Initial release [https://github.com/nextflow-io/nextflow/commit/4d57979526d807a0d146697eb9c47d22696b0587]
