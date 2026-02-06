#!/bin/bash

# Java-Sleuth Startup Script

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

JAR_FILE="$(ls -1t "$SCRIPT_DIR"/core/target/*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
if [ -z "${JAR_FILE}" ]; then
    JAR_FILE="$(ls -1t "$SCRIPT_DIR"/target/*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
fi
if [ -z "${JAR_FILE}" ]; then
    JAR_FILE="$(ls -1t "$SCRIPT_DIR"/lib/*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
fi
if [ -z "${JAR_FILE}" ]; then
    JAR_FILE="$(ls -1t "$SCRIPT_DIR"/../lib/*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
fi

if [ -z "${JAR_FILE}" ] || [ ! -f "$JAR_FILE" ]; then
    echo "Java-Sleuth agent/launcher JAR not found under:"
    echo "  - $SCRIPT_DIR/core/target/"
    echo "  - $SCRIPT_DIR/target/"
    echo "  - $SCRIPT_DIR/lib/"
    echo "  - $SCRIPT_DIR/../lib/"
    echo "Please build the project first with: mvn clean package"
    exit 1
fi

# Check if tools.jar is available (required for JDK < 9)
JAVA_SPEC="$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.specification.version =/ {print $2; exit}')"
JAVA_MAJOR=""
if [ -n "${JAVA_SPEC}" ]; then
    if [[ "${JAVA_SPEC}" == 1.* ]]; then
        JAVA_MAJOR="${JAVA_SPEC#1.}"
        JAVA_MAJOR="${JAVA_MAJOR%%.*}"
    else
        JAVA_MAJOR="${JAVA_SPEC%%.*}"
    fi
fi

CLASSPATH="$JAR_FILE"
if [ -n "${JAVA_MAJOR}" ] && [ "${JAVA_MAJOR}" -lt 9 ]; then
    TOOLS_JAR="${JAVA_HOME:-}/lib/tools.jar"
    if [ -z "${JAVA_HOME:-}" ] || [ ! -f "$TOOLS_JAR" ]; then
        echo "tools.jar not found. Please set JAVA_HOME correctly for JDK < 9"
        exit 1
    fi
    CLASSPATH="$JAR_FILE:$TOOLS_JAR"
fi

echo "Starting Java-Sleuth..."
if [ -n "${JAVA_MAJOR}" ]; then
    echo "Java Major Version: $JAVA_MAJOR"
fi
echo "JAR File: $JAR_FILE"

java -cp "$CLASSPATH" com.javasleuth.launcher.SleuthLauncher "$@"
