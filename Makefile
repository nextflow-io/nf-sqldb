config ?= compileClasspath
version ?= $(shell grep 'Plugin-Version' plugins/nf-sqldb/src/resources/META-INF/MANIFEST.MF | awk '{ print $$2 }')

ifdef module 
mm = :${module}:
else 
mm = 
endif 

clean:
	rm -rf .nextflow*
	rm -rf work
	rm -rf build
	rm -rf plugins/*/build
	./gradlew clean

compile:
	./gradlew compileGroovy
	@echo "DONE `date`"


check:
	./gradlew check


#
# Show dependencies try `make deps config=runtime`, `make deps config=google`
#
deps:
	./gradlew -q ${mm}dependencies --configuration ${config}

deps-all:
	./gradlew -q dependencyInsight --configuration ${config} --dependency ${module}

#
# Refresh SNAPSHOTs dependencies
#
refresh:
	./gradlew --refresh-dependencies 

#
# Run all tests or selected ones
#
test:
ifndef class
	./gradlew ${mm}test
else
	./gradlew ${mm}test --tests ${class}
endif

assemble:
	./gradlew assemble

#
# generate build zips under build/plugins
# you can install the plugin copying manually these files to $HOME/.nextflow/plugins
#
buildPlugins:
	./gradlew copyPluginZip

#
# Upload JAR artifacts to Maven Central
#
upload:
	./gradlew upload


upload-plugins:
	./gradlew plugins:upload

publish-index:
	./gradlew plugins:publishIndex

# Install the plugin into local nextflow plugins dir
install:
	./gradlew copyPluginZip
	rm -rf ${HOME}/.nextflow/plugins/nf-sqldb-${version}
	cp -r build/plugins/nf-sqldb-${version} ${HOME}/.nextflow/plugins/nf-sqldb-${version}


publish-jar:
	./gradlew plugins:nf-sqldb:publishMavenPublicationToMavenRepository
