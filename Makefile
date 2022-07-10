#
# Show dependencies try `make deps config=runtime`, `make deps config=google`
#
deps: FORCE
	./gradlew -q :plugins:nf-sqldb:dependencies --configuration runtimeClasspath

FORCE: ;
