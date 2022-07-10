package test

import spock.lang.Specification

import groovy.util.logging.Slf4j

/**
 * Base specification class - It wraps each test into begin-close "test name" strigs
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
class BaseSpec extends Specification {

    def setup() {
        log.info "TEST BEGIN [${specificationContext.currentIteration.name}]"
    }

    def cleanup() {
        log.info "TEST CLOSE [${specificationContext.currentIteration.name}]"
    }


}
