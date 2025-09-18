#!/bin/bash

# Java-Sleuth Startup Script

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_FILE="$SCRIPT_DIR/target/java-sleuth-1.0.0-jar-with-dependencies.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "Java-Sleuth JAR file not found: $JAR_FILE"
    echo "Please build the project first with: mvn clean package"
    exit 1
fi

# Check if tools.jar is available (required for JDK < 9)
JAVA_VERSION=$(java -version 2>&1 | grep -oP 'version "([0-9]+)' | grep -oP '[0-9]+' | head -1)
if [ "$JAVA_VERSION" -lt 9 ]; then
    TOOLS_JAR="$JAVA_HOME/lib/tools.jar"
    if [ ! -f "$TOOLS_JAR" ]; then
        echo "tools.jar not found. Please set JAVA_HOME correctly for JDK < 9"
        exit 1
    fi
    CLASSPATH="$JAR_FILE:$TOOLS_JAR"
else
    CLASSPATH="$JAR_FILE"
fi

echo "Starting Java-Sleuth..."
echo "Java Version: $JAVA_VERSION"
echo "JAR File: $JAR_FILE"

java -cp "$CLASSPATH" com.javasleuth.launcher.SleuthLauncher "$@"