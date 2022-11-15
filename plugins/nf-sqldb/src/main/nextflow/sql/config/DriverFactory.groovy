package nextflow.sql.config


/**
 * @author : jorge <jorge.aguilera@seqera.io>
 *
 */
@Singleton
class DriverFactory {

    Map<String, String> drivers = [:]

    void initDefaultDrivers(){
        DriverFactory.instance.drivers.'h2' = 'org.h2.Driver'
        DriverFactory.instance.drivers.'sqlite' = 'org.sqlite.JDBC'
        DriverFactory.instance.drivers.'mysql'= 'com.mysql.cj.jdbc.Driver'
        DriverFactory.instance.drivers.'mariadb'= 'org.mariadb.jdbc.Driver'
        DriverFactory.instance.drivers.'postgresql'= 'org.postgresql.Driver'
        DriverFactory.instance.drivers.'duckdb'= 'org.duckdb.DuckDBDriver'
        DriverFactory.instance.drivers.'awsathena'= 'com.simba.athena.jdbc.Driver'
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
