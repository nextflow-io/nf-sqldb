# Using Secrets for Database Credentials

For production deployments, it's recommended to use Nextflow secrets instead of hardcoding credentials in configuration files. This is especially important when working with cloud databases like AWS Athena.

## Configuration with Secrets

When using [Nextflow secrets](https://www.nextflow.io/docs/latest/secrets.html) (available in Nextflow 25.04.0+), you can reference workspace or user-level secrets in your database configuration:

```groovy
sql {
    db {
        athena {
            url = 'jdbc:awsathena://AwsRegion=us-east-1;S3OutputLocation=s3://bucket;Workgroup=workgroup'
            user = secrets.ATHENA_USER
            password = secrets.ATHENA_PASSWORD
            driver = 'com.simba.athena.jdbc.Driver'
        }
    }
}
```

## Setting Up Secrets in Seqera Platform

1. **Workspace Secrets**: Navigate to your workspace → Secrets → Add secret
2. **User Secrets**: Navigate to Your profile → Secrets → Add secret  
3. Create secrets with names matching your configuration (e.g., `ATHENA_USER`, `ATHENA_PASSWORD`)

## Troubleshooting Secrets Issues

If you encounter authentication errors like "Missing credentials error", verify:

- **Secret Names**: Ensure secret names in your configuration match exactly (case-sensitive)
- **Permissions**: Verify you have access to workspace secrets or have defined user-level secrets
- **Nextflow Version**: Secrets require Nextflow >=25.04.0
- **Secret Values**: Ensure secrets contain valid credentials (no empty values)

Common error patterns:
- `user=sa; password=null` indicates secrets were not resolved
- `Unresolved secret detected` means secret names don't match or aren't accessible

## Local Development

For local testing, you can use the Nextflow secrets command:

```bash
# Set secrets locally
nextflow secrets set ATHENA_USER "your-username"  
nextflow secrets set ATHENA_PASSWORD "your-password"

# List secrets
nextflow secrets list

# Run pipeline with secrets
nextflow run your-pipeline.nf
```