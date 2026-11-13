# Querying CSV files with H2

This plugin supports the [H2](https://www.h2database.com/html/main.html) database engine, which can query CSV files like database tables using SQL statements.

For example, create a CSV file using the snippet below:

```bash
cat <<EOF > test.csv
foo,bar
1,hello
2,ciao
3,hola
4,bonjour
EOF
```

Then query it in a Nextflow script:

```nextflow
include { sqlQuery } from 'plugin/nf-sqldb'

workflow {
    channel.fromList(sqlQuery(query: "SELECT * FROM CSVREAD('test.csv') WHERE foo >= 2"))
        .view()
}
```

The `CSVREAD` function provided by the H2 database engine allows you to query any CSV file in your filesystem. As shown in the example, you can use standard SQL clauses like `SELECT` and `WHERE` to define your query.
