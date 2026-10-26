#!/bin/sh
exec java -Dmaven.multiModuleProjectDirectory="$(dirname "$0")" -classpath "$(dirname "$0")/.mvn/wrapper/maven-wrapper.jar" org.apache.maven.wrapper.MavenWrapperMain "$@"
