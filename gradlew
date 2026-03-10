#!/bin/sh
# Gradle wrapper script with auto-bootstrap
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DEFAULT_JVM_OPTS="-Xmx512m -Xms64m"

warn() { echo "$*"; }
die() { echo; echo "$*"; echo; exit 1; }

# Locate java
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
    [ -f "$JAVACMD" ] || die "JAVA_HOME is set but points to no valid Java installation."
else
    JAVACMD="java"
    command -v java >/dev/null 2>&1 || die "ERROR: java not found in PATH."
fi

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

# Auto-download wrapper jar if missing
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "gradle-wrapper.jar not found, downloading..."
    WRAPPER_JAR_URL="https://github.com/gradle/gradle/raw/v8.8.0/gradle/wrapper/gradle-wrapper.jar"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$WRAPPER_JAR_URL" -o "$WRAPPER_JAR" || die "Failed to download gradle-wrapper.jar"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$WRAPPER_JAR_URL" -O "$WRAPPER_JAR" || die "Failed to download gradle-wrapper.jar"
    else
        die "Cannot download gradle-wrapper.jar: neither curl nor wget found. Download manually from:\n  $WRAPPER_JAR_URL\nand place it at: $WRAPPER_JAR"
    fi
    echo "gradle-wrapper.jar downloaded successfully."
fi

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
