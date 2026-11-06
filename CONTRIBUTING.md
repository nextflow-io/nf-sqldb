# nf-sqldb

Contributions are welcome. Fork [this repository](https://github.com/nextflow-io/nf-sqldb) and open a pull request to propose changes. Consider submitting an [issue](https://github.com/nextflow-io/nf-sqldb/issues/new) to discuss any proposed changes with the maintainers before submitting a pull request.

## Development

Run the unit tests:

```bash
make test
```

Run the integration tests against real databases (Postgres and MySQL). This requires Docker:

```bash
make integration-test
```

Build and install the plugin to your local Nextflow installation:

```bash
make install
```

Run with Nextflow as usual:

```bash
nextflow run examples/sql-execution -plugins nf-sqldb@<version>
```

## Publishing

Follow these steps to publish a new version of the plugin:

1. Update the [version file](./VERSION).

2. Update the [changelog](./CHANGELOG.md): rename `## Unreleased` to `## [<version>] - <YYYY-MM-DD>`.

3. Merge these changes into `master` as a single commit with the message `Release <version>` (e.g. `Release 0.8.0`).

4. Run the [Release](https://github.com/nextflow-io/nf-sqldb/actions/workflows/release.yml) workflow on `master`. It runs the unit tests and then, depending on the selected options:

   * **Publish to the plugin registry**: publishes the plugin to the [Nextflow plugin registry](https://registry.nextflow.io) and creates a GitHub release for the version. Snapshot versions are rejected.
   * **Publish to Maven**: publishes the plugin jar as `io.nextflow:nf-sqldb` to the `s3://maven.seqera.io` Maven repository, for downstream plugins such as nf-databricks and nf-trino. Snapshot versions are published to the snapshots repository.

   Versions that were already published are skipped, so the workflow can be re-run after a partial failure.

The workflow requires the `NPR_API_KEY` and `SEQERA_MAVEN_SECRET_KEY` secrets and the `SEQERA_MAVEN_ACCESS_KEY` variable. To release locally instead, set `NPR_API_KEY`, `AWS_ACCESS_KEY_ID`, and `AWS_SECRET_ACCESS_KEY` and run `make release`.
