package nextflow.sql

import nextflow.sql.config.DriverRegistry


/**
 * @author : jorge <jorge.aguilera@seqera.io>
 *
 */
class BigQueryDriverRegistry extends DriverRegistry {

    BigQueryDriverRegistry(){
        super()
        addDriver('bigquery','com.simba.googlebigquery.jdbc.Driver')
    }

}
