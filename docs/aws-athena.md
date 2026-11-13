# AWS Athena integration

## Pre-requisites

1. An AWS Athena S3 data-source
2. An AWS Glue crawler and database
3. The AWS Glue crawler has been run at least once to populate the tables in the database

## Usage

The following example uses the [NCBI SRA Metadata](https://www.ncbi.nlm.nih.gov/sra/docs/sra-athena/) as the data source. Refer to the [tutorial from NCBI](https://www.youtube.com/watch?v=_F4FhcDWSJg&ab_channel=TheNationalLibraryofMedicine) for setting up the AWS resources correctly.

### Configuration

Adjust the following configuration to match your setup:

```nextflow config
params {
    aws_glue_db = 'sra-glue-db'
    aws_glue_db_table = 'metadata'
}

plugins {
    id 'nf-sqldb'
}

sql {
    db {
        athena {
            url = 'jdbc:awsathena://AwsRegion=<YOUR_AWS_REGION>;S3OutputLocation=<YOUR_S3_BUCKET>'
            user = secrets.ATHENA_USER
            password = secrets.ATHENA_PASSWORD
        }
    }
}
```

The `ATHENA_USER` and `ATHENA_PASSWORD` secrets should contain your AWS access key and secret key, respectively. See [secrets.md](secrets.md) for how to define them.

### Pipeline

Execute the following Nextflow pipeline:

```nextflow
include { sqlQuery } from 'plugin/nf-sqldb'

workflow {
    def query = """
        SELECT *
        FROM \"${params.aws_glue_db}\".${params.aws_glue_db_table}
        WHERE organism = 'Mycobacterium tuberculosis'
        LIMIT 10
        """

    channel.fromList(sqlQuery(db: 'athena', query: query)).view()
}
```

### Output

The pipeline script prints a record for each row of the query results, which maps each column name to its value.
