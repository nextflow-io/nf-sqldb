# Changelog

All notable changes to the nf-sqldb plugin will be documented here.

See [Keep a Changelog](https://keepachangelog.com/) for recommendations on how to structure this file.

## Unreleased

- Migrate build to the Nextflow Gradle plugin (#42)
- Require Nextflow 25.10.0 or later (#43)
- Generate the plugin spec, including the `sql` config scope (#43)
- Remove DSL1 support (#41)
- Run integration tests against Postgres and MySQL (#11)
- `sqlInsert` now works with DuckDB, which supports batch inserts as of `duckdb_jdbc` 1.3.0.0 (#19)
- Fix `sqlInsert` with inferred columns to match table and column names ignoring case (e.g. Postgres and MySQL lower-case columns, `into: 'FOO'` on Postgres)
- Reject `sqlInsert` map records with keys that differ only by case
- Map record keys are now matched to the `columns` option ignoring case (previously a key such as `ID` for column `id` inserted NULL)
- Don't append a trailing semicolon to queries and statements, which Trino and other JDBC drivers reject (#16)
- Fix paginated queries (`batchSize`) with a trailing semicolon, newline or `--` comment
- Don't reject paginated queries whose identifiers merely contain `limit` or `offset` (e.g. `rate_limit`)
- Fix `sqlExecute` returning a row count instead of `null` for multi-line DDL statements

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
