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

Follow these steps to package, upload, and publish the plugin:

1. Update the [version file](./VERSION).

2. Update the [changelog](./CHANGELOG.md).

3. Run `make release` to build and publish the plugin. This task requires the following credentials:

   * `NPR_API_KEY` (or the `npr.apiKey` Gradle property): API key for the [Nextflow plugin registry](https://registry.nextflow.io).
   * `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` (or the `aws_access_key_id` and `aws_secret_access_key` Gradle properties): AWS credentials with write access to the `s3://maven.seqera.io` Maven repository, where the plugin jar is published as `io.nextflow:nf-sqldb` for downstream plugins such as nf-databricks and nf-trino.

4. Make a [GitHub release](https://github.com/nextflow-io/nf-sqldb/releases).
