# Changelog

All notable changes to the nf-sqldb plugin will be documented here.

See [Keep a Changelog](https://keepachangelog.com/) for recommendations on how to structure this file.

## [0.8.0] - 2026-11-06

### Breaking changes

- Require Nextflow 26.04.0 or later (#43). Plugins that depend on the nf-sqldb Maven artifact must also require 26.04.0 when upgrading. Version 0.7.1 is the last release for older Nextflow versions
- Remove DSL1 support (#41)
- Remove the `SqlDataSource(Map, SqlDataSource)` constructor and `SqlDataSource.setDEFAULT()`
- Databases configured without `url` get their own in-memory H2 database instead of sharing the `default` one, unless `sql.db.default.url` is set (#50)

### New features

- Add the `sqlQuery` function, which returns the rows of a query as a list of records and supports static typing (#52)
- Add the `params` and `batch` options to `sqlExecute`, for bind parameters and batch execution (#51)
- Validate the `sql` config options, which are now included in the plugin spec (#43)

### Deprecations

- Deprecate `fromQuery` and `sqlInsert`, which are not supported with static typing, in favor of `sqlQuery` and `sqlExecute`

### Fixes

- Fix the built-in `default` H2 database losing its data between calls (#50)
- Don't append a trailing semicolon to statements, which Trino and other drivers reject (#45)
- Fix `sqlExecute` returning a row count instead of `null` for multi-line DDL statements (#46)
- Fix paginated queries (`batchSize`) with a trailing semicolon or comment, or with identifiers containing `limit` or `offset` (#45)
- Fix `sqlInsert` column inference with case-sensitive names, qualified names, same-named tables in other schemas, and `_` or `%` in table names (#44, #48)
- Fix `sqlInsert` with DuckDB (#19), with SQLite when several operators write to the same database (#21, #54), and with drivers that don't support transactions, such as Databricks (#55)
- Fix `sqlExecute` rejecting the output of `collect` as `batch` under static typing (#57)
- Don't print a duplicate warning to stderr when a statement fails (#58)
- Reduce the plugin size by removing the Windows native libraries from the bundled DuckDB and SQLite drivers, which Nextflow doesn't use (#59)

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
