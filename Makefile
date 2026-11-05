# Build the plugin
assemble:
	./gradlew assemble

clean:
	rm -rf .nextflow*
	rm -rf work
	rm -rf build
	./gradlew clean

# Run plugin unit tests
test:
	./gradlew test

# Run integration tests against real databases (requires Docker)
integration-test:
	./gradlew integrationTest

# Build the plugin and run all checks
check:
	./gradlew check

# Install the plugin into local nextflow plugins dir
install:
	./gradlew install

# Publish the plugin to the registry and the jar to the Seqera Maven repository
release:
	./gradlew releasePluginIfNotExists publishMavenPublicationToMavenRepository

# Publish the plugin jar to the Seqera Maven repository
publish-jar:
	./gradlew publishMavenPublicationToMavenRepository
