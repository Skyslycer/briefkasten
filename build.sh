#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -x /usr/lib/jvm/java-17-temurin/bin/java ]; then
    JAVA_HOME=/usr/lib/jvm/java-17-temurin
    export JAVA_HOME
fi
GRADLE_USER_HOME="$PWD/.tooling/gradle-home"
export GRADLE_USER_HOME
exec ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug "$@"
