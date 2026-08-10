/*
 * Copyright 2020-2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package nextflow.sql.config

import java.sql.Connection
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

import nextflow.util.Duration
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class SqlDataSourceTest extends Specification {

    def 'should configure datasource'  () {

        given:
        def CONFIG = '''
            dataSources {
                'default' {
                    url = 'jdbc:h2:mem:' 
                    driver = 'org.h2.Driver'
                    user = 'sa'
                }
                
                myDb1 {
                    url = 'jdbc:h2:mem:' 
                    driver = 'org.h2.Driver'
                    user = 'sa'
                    password = null
                }
            
                myDb2 {
                    url = 'jdbc:postgresql://host:port/database'
                    driver = 'org.postgresql.Driver'
                    user = 'xyz'
                    password = 'foo'                    
                }
                
            }
        '''

    }

    def 'should map url to driver' () {
        given:
        def helper = new SqlDataSource([:])

        expect:
        helper.urlToDriver(JBDC_URL) == DRIVER
        where:
        JBDC_URL                        | DRIVER
        'jdbc:postgresql:database'      | 'org.postgresql.Driver'
        'jdbc:sqlite:database'          | 'org.sqlite.JDBC'
        'jdbc:h2:mem:'                  | 'org.h2.Driver'
        'jdbc:mysql:some-host'          | 'com.mysql.cj.jdbc.Driver'
        'jdbc:mariadb:other-host'       | 'org.mariadb.jdbc.Driver'
        'jdbc:duckdb:'                  | 'org.duckdb.DuckDBDriver'
        'jdbc:awsathena:'               | 'com.simba.athena.jdbc.Driver'
    }

    def 'should get default config' () {
        given:
        def ds = new SqlDataSource([:])
        expect:
        ds.url == SqlDataSource.DEFAULT_URL
        ds.driver == SqlDataSource.DEFAULT_DRIVER
        ds.user == SqlDataSource.DEFAULT_USER
        ds.password == null
    }


    def 'should get postgresql config' () {
        given:
        def ds = new SqlDataSource([url:'jdbc:postgresql:some-host'])
        expect:
        ds.url == 'jdbc:postgresql:some-host'
        ds.driver == 'org.postgresql.Driver'
        ds.user == SqlDataSource.DEFAULT_USER
        ds.password == null
    }

    def 'should get custom config' () {
        given:
        def config = [
                url:'jdbc:xyz:host-name',
                driver:'this.that.Driver',
                user: 'foo',
                password: 'secret']
        and:
        def ds = new SqlDataSource(config)

        expect:
        ds.url == 'jdbc:xyz:host-name'
        ds.driver == 'this.that.Driver'
        ds.user == 'foo'
        ds.password == 'secret'
    }

    def 'should convert to map' () {
        when:
        def ds = new SqlDataSource(url:'x', driver: 'y', user: 'w', password: 'z')
        then:
        ds.toMap().url == 'x'
        ds.toMap().driver == 'y'
        ds.toMap().user == 'w'
        ds.toMap().password == 'z'
    }

    def 'should validate equals & hashcode' () {
        given:
        def ds1 = new SqlDataSource(url:'x', driver: 'y', user: 'w', password: 'z')
        def ds2 = new SqlDataSource(url:'x', driver: 'y', user: 'w', password: 'z')
        def ds3 = new SqlDataSource(url:'p', driver: 'q', user: 'r', password: 'v')
        expect:
        ds1 == ds2
        ds1 != ds3
        and:
        ds1.hashCode() == ds2.hashCode()
        ds1.hashCode() != ds3.hashCode()
    }

    def 'should detect unresolved secrets' () {
        when:
        new SqlDataSource([user: pattern])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Unresolved secret detected")
        e.message.contains("workspace secrets are not properly configured")

        where:
        pattern << ['secrets.ATHENA_USER', '[secret]']
    }

    def 'should handle various credential inputs' () {
        when:
        def ds = new SqlDataSource([user: userInput, password: passInput])
        then:
        ds.user == expectedUser
        ds.password == expectedPass

        where:
        userInput    | passInput    | expectedUser              | expectedPass
        'validuser'  | 'validpass'  | 'validuser'              | 'validpass'
        null         | null         | SqlDataSource.DEFAULT_USER| null
        ''           | ''           | SqlDataSource.DEFAULT_USER| null
    }

    def 'should connect and run a query against h2 with a timeout configured' () {
        given:
        def ds = new SqlDataSource([url: 'jdbc:h2:mem:timeout-smoke;DB_CLOSE_DELAY=-1', driver: 'org.h2.Driver', user: 'sa', timeout: '5s'])

        when:
        def conn = ds.connect()
        def rs = conn.createStatement().executeQuery('SELECT 1')

        then:
        rs.next()
        rs.getInt(1) == 1

        cleanup:
        conn?.close()
    }

    def 'should resolve timeout config' () {
        expect:
        new SqlDataSource([timeout: value]).timeout == expected
        where:
        value           | expected
        null             | null
        '5s'             | Duration.of('5s')
        '500ms'          | Duration.of('500ms')
        5                | Duration.of(5_000)
        Duration.of('2m')| Duration.of('2m')
    }

    def 'should leave timeout unset by default' () {
        expect:
        new SqlDataSource([:]).timeout == null
        new SqlDataSource([:]).timeoutSecs == null
    }

    def 'should convert timeout to seconds rounding up' () {
        expect:
        new SqlDataSource([timeout: value]).timeoutSecs == secs
        where:
        value    | secs
        '5s'     | 5
        '1500ms' | 2
        '100ms'  | 1
        '0s'     | 0
    }

    def 'should inherit timeout from fallback' () {
        given:
        def fallback = new SqlDataSource([timeout: '5s'])
        expect:
        new SqlDataSource([:], fallback).timeout == Duration.of('5s')
        new SqlDataSource([timeout: '10s'], fallback).timeout == Duration.of('10s')
    }

    def 'should treat a configured zero timeout as no timeout, not immediate failure' () {
        given:
        DriverManager.registerDriver(new FakeDriver())
        def ds = new SqlDataSource([url: FakeDriver.URL, driver: FakeDriver.name, user: 'sa', timeout: '0s'])

        when:
        def conn = ds.connect()

        then:
        ds.timeoutSecs == 0
        conn != null

        cleanup:
        DriverManager.deregisterDriver(new FakeDriver())
    }

    def 'should bound a configured connect that blocks inside Driver#connect itself' () {
        given:
        // StuckFakeDriver never applies any login timeout, modeling a driver
        // (e.g. trino-jdbc) that blocks forever in its own connect()
        DriverManager.registerDriver(new StuckFakeDriver())
        def ds = new SqlDataSource([url: StuckFakeDriver.URL, driver: StuckFakeDriver.name, user: 'sa', timeout: '1s'])
        StuckFakeDriver.onConnect = { Thread.sleep(60_000) }

        when:
        def start = System.nanoTime()
        ds.connect()

        then:
        def e = thrown(SQLTimeoutException)
        e.message.contains('1s')
        (System.nanoTime() - start) < TimeUnit.SECONDS.toNanos(5)

        cleanup:
        StuckFakeDriver.onConnect = null
        DriverManager.deregisterDriver(new StuckFakeDriver())
    }

    def 'should not exceed a sub-second configured timeout by more than a small margin when the driver never returns' () {
        given:
        DriverManager.registerDriver(new StuckFakeDriver())
        def ds = new SqlDataSource([url: StuckFakeDriver.URL, driver: StuckFakeDriver.name, user: 'sa', timeout: '100ms'])
        StuckFakeDriver.onConnect = { Thread.sleep(60_000) }

        when:
        def start = System.nanoTime()
        ds.connect()

        then:
        thrown(SQLTimeoutException)
        // bounded with nanosecond precision, not rounded up to whole seconds
        (System.nanoTime() - start) < TimeUnit.MILLISECONDS.toNanos(900)

        cleanup:
        StuckFakeDriver.onConnect = null
        DriverManager.deregisterDriver(new StuckFakeDriver())
    }

    def 'should close a connection that completes after the configured deadline instead of leaking it' () {
        given:
        DriverManager.registerDriver(new StuckFakeDriver())
        def ds = new SqlDataSource([url: StuckFakeDriver.URL, driver: StuckFakeDriver.name, user: 'sa', timeout: '200ms'])
        def closed = new java.util.concurrent.CountDownLatch(1)
        StuckFakeDriver.onConnect = {
            final deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600)
            while( System.nanoTime() < deadline ) {
                try { Thread.sleep(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) + 1) }
                catch( InterruptedException ignored ) { /* keep sleeping until the deadline regardless */ }
            }
        }
        StuckFakeDriver.onClose = { closed.countDown() }

        when:
        ds.connect()

        then:
        thrown(SQLTimeoutException)
        closed.await(5, TimeUnit.SECONDS)

        cleanup:
        StuckFakeDriver.onConnect = null
        StuckFakeDriver.onClose = null
        DriverManager.deregisterDriver(new StuckFakeDriver())
    }

    def 'should fail fast without spawning a new thread once abandoned connect attempts exhaust the cap, and release slots when they finally return' () {
        given:
        DriverManager.registerDriver(new StuckFakeDriver())
        StuckFakeDriver.connectAttempts.set(0)
        def ds = new SqlDataSource([url: StuckFakeDriver.URL, driver: StuckFakeDriver.name, user: 'sa', timeout: '100ms'])
        def release = new java.util.concurrent.CountDownLatch(1)
        StuckFakeDriver.onConnect = {
            while( release.getCount() > 0 ) {
                try { release.await(50, TimeUnit.MILLISECONDS) }
                catch( InterruptedException ignored ) { /* ignore shutdownNow interruption, keep waiting */ }
            }
        }
        def cap = SqlDataSource.MAX_ABANDONED_CONNECT_ATTEMPTS

        when:
        // exhaust the cap: each of these times out, abandoning its stuck background thread
        cap.times {
            try { ds.connect() } catch( SQLTimeoutException ignored ) {}
        }

        then:
        StuckFakeDriver.connectAttempts.get() == cap

        when:
        // the cap is now exhausted: the very next attempt must fail immediately,
        // without ever spawning another background thread (no new call into the driver)
        def attemptsBefore = StuckFakeDriver.connectAttempts.get()
        def start = System.nanoTime()
        ds.connect()

        then:
        def e = thrown(SQLTimeoutException)
        e.message.contains('stuck')
        (System.nanoTime() - start) < TimeUnit.SECONDS.toNanos(1)
        Thread.sleep(300)
        StuckFakeDriver.connectAttempts.get() == attemptsBefore

        when:
        // release every abandoned attempt: their slots must be returned, letting a
        // fresh connect (well within budget) succeed again
        release.countDown()
        StuckFakeDriver.onConnect = null
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        def conn = null
        while( conn == null && System.nanoTime() < deadline ) {
            try { conn = ds.connect() }
            catch( SQLTimeoutException ignored ) { Thread.sleep(50) }
        }

        then:
        conn != null

        cleanup:
        StuckFakeDriver.onConnect = null
        StuckFakeDriver.connectAttempts.set(0)
        DriverManager.deregisterDriver(new StuckFakeDriver())
    }



    /**
     * Deterministic fake JDBC driver used for a plain connect, without any real I/O.
     */
    static class FakeDriver implements java.sql.Driver {
        static final String URL = 'jdbc:faketimeout:test'

        @Override
        Connection connect(String url, java.util.Properties info) throws SQLException {
            if( !acceptsURL(url) )
                return null
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.classLoader,
                [Connection] as Class[],
                { proxy, method, args -> method.name == 'isClosed' ? false : null } as java.lang.reflect.InvocationHandler
            )
        }

        @Override
        boolean acceptsURL(String url) throws SQLException { url == URL }

        @Override
        DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) throws SQLException { new DriverPropertyInfo[0] }

        @Override
        int getMajorVersion() { 1 }

        @Override
        int getMinorVersion() { 0 }

        @Override
        boolean jdbcCompliant() { false }

        @Override
        Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { null }
    }

    /**
     * Fake driver whose {@code connect} runs an optional hook (e.g. an unbounded sleep)
     * before returning -- simulates a driver that blocks indefinitely on the underlying
     * transport, regardless of any configured timeout.
     */
    static class StuckFakeDriver implements java.sql.Driver {
        static final String URL = 'jdbc:fakestucktimeout:test'
        static volatile Closure onConnect = null
        static volatile Closure onClose = null
        static volatile java.util.concurrent.atomic.AtomicInteger connectAttempts = new java.util.concurrent.atomic.AtomicInteger()

        @Override
        Connection connect(String url, java.util.Properties info) throws SQLException {
            if( !acceptsURL(url) )
                return null
            connectAttempts.incrementAndGet()
            onConnect?.call()
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.classLoader,
                [Connection] as Class[],
                { proxy, method, args ->
                    if( method.name == 'isClosed' )
                        return false
                    if( method.name == 'close' )
                        onClose?.call()
                    return null
                } as java.lang.reflect.InvocationHandler
            )
        }

        @Override
        boolean acceptsURL(String url) throws SQLException { url == URL }

        @Override
        DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) throws SQLException { new DriverPropertyInfo[0] }

        @Override
        int getMajorVersion() { 1 }

        @Override
        int getMinorVersion() { 0 }

        @Override
        boolean jdbcCompliant() { false }

        @Override
        Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { null }
    }
}
