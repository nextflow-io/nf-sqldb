# Databricks integration

## Running Integration Tests

To run the integration tests, you'll need to set up the following environment variables:

### Databricks Integration Tests

For Databricks integration tests, you need to set:

```bash
export DATABRICKS_JDBC_URL="jdbc:databricks://<workspace-url>:443/default;transportMode=http;ssl=1;httpPath=sql/protocolv1/o/<org-id>/<workspace-id>"
export DATABRICKS_TOKEN="<your-databricks-token>"
```

You can get these values from your Databricks workspace:

1. The JDBC URL can be found in the Databricks SQL endpoint connection details
2. The token can be generated from your Databricks user settings

After setting up the required environment variables, you can run the integration tests using:

```bash
./gradlew test
```

<!-- Note: Integration tests are skipped by default when running in smoke test mode (when `NXF_SMOKE` environment variable is set). -->
