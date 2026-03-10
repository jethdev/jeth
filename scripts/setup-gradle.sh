#!/bin/sh
# Download the gradle-wrapper.jar if missing.
# Run this once after cloning, or let gradlew do it automatically.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$SCRIPT_DIR/../gradle/wrapper/gradle-wrapper.jar"
WRAPPER_JAR_URL="https://github.com/gradle/gradle/raw/v8.8.0/gradle/wrapper/gradle-wrapper.jar"
EXPECTED_SHA256="e6047e88c4a31c40c29cc6f02f1b9d44e6d56d2a7c8e89f87e47f38b74be2b7b"

if [ -f "$WRAPPER_JAR" ]; then
    echo "gradle-wrapper.jar already present."
    exit 0
fi

echo "Downloading gradle-wrapper.jar..."
if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$WRAPPER_JAR_URL" -o "$WRAPPER_JAR"
elif command -v wget >/dev/null 2>&1; then
    wget -q "$WRAPPER_JAR_URL" -O "$WRAPPER_JAR"
else
    echo "Error: neither curl nor wget found." >&2
    exit 1
fi
echo "Done. You can now run ./gradlew test"
