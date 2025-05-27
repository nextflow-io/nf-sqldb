package nextflow.sql.config


/**
 * A simple map of driver names and class implementation
 *
 * @author : jorge <jorge.aguilera@seqera.io>
 *
 */
class DriverRegistry {

    Map<String, String> drivers = [:]

    static DriverRegistry DEFAULT = new DriverRegistry()

    DriverRegistry(){
        initDefaultDrivers()
    }

    protected void initDefaultDrivers(){
        drivers.'h2' = 'org.h2.Driver'
        drivers.'sqlite' = 'org.sqlite.JDBC'
        drivers.'mysql'= 'com.mysql.cj.jdbc.Driver'
        drivers.'mariadb'= 'org.mariadb.jdbc.Driver'
        drivers.'postgresql'= 'org.postgresql.Driver'
        drivers.'duckdb'= 'org.duckdb.DuckDBDriver'
        drivers.'awsathena'= 'com.simba.athena.jdbc.Driver'
    }

    void addDriver(String name, String driver){
        drivers[name] = driver
    }

    protected String urlToDriver(String url) {
        if( !url ) return null
        if( !url.startsWith('jdbc:') ) throw new IllegalArgumentException("Invalid database JDBC connection url: $url")
        String name = url.tokenize(':')[1];
        if( !drivers.containsKey(name) )
            return null
        return drivers[name]
    }

}
