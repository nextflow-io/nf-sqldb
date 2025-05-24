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
            user = '<YOUR_AWS_ACCESS_KEY>'
            password = '<YOUR_AWS_SECRET_KEY>'
        }
    }
}
```

### Pipeline

Execute the following Nextflow pipeline:

```nextflow
include { fromQuery } from 'plugin/nf-sqldb'

def sqlQuery = """
    SELECT *
    FROM \"${params.aws_glue_db}\".${params.aws_glue_db_table}
    WHERE organism = 'Mycobacterium tuberculosis'
    LIMIT 10;
    """

Channel.fromQuery(sqlQuery, db: 'athena').view()
```

### Output

The pipeline script will print the query results to the console:

```console
[SRR6797500, WGS, SAN RAFFAELE, public, SRX3756197, 131677, Illumina HiSeq 2500, PAIRED, RANDOM, GENOMIC, ILLUMINA, SRS3011891, SAMN08629009, Mycobacterium tuberculosis, SRP128089, 2018-03-02, PRJNA428596, 165, null, 201, 383, null, 131677_WGS, Pathogen.cl, null, uncalculated, uncalculated, null, null, null, bam, sra, s3, s3.us-east-1, {k=assemblyname, v=GCF_000195955.2}, {k=bases, v=383901808}, {k=bytes, v=173931377}, {k=biosample_sam, v=MTB131677}, {k=collected_by_sam, v=missing}, {k=collection_date_sam, v=2010/2014}, {k=host_disease_sam, v=Tuberculosis}, {k=host_sam, v=Homo sapiens}, {k=isolate_sam, v=Clinical isolate18}, {k=isolation_source_sam_ss_dpl262, v=Not applicable}, {k=lat_lon_sam, v=Not collected}, {k=primary_search, v=131677}, {k=primary_search, v=131677_210916_BGD_210916_100.gatk.bam}, {k=primary_search, v=131677_WGS}, {k=primary_search, v=428596}, {k=primary_search, v=8629009}, {k=primary_search, v=PRJNA428596}, {k=primary_search, v=SAMN08629009}, {k=primary_search, v=SRP128089}, {k=primary_search, v=SRR6797500}, {k=primary_search, v=SRS3011891}, {k=primary_search, v=SRX3756197}, {k=primary_search, v=bp0}, {"assemblyname": "GCF_000195955.2", "bases": 383901808, "bytes": 173931377, "biosample_sam": "MTB131677", "collected_by_sam": ["missing"], "collection_date_sam": ["2010/2014"], "host_disease_sam": ["Tuberculosis"], "host_sam": ["Homo sapiens"], "isolate_sam": ["Clinical isolate18"], "isolation_source_sam_ss_dpl262": ["Not applicable"], "lat_lon_sam": ["Not collected"], "primary_search": "131677"}]
```
