#!/bin/bash

# Java-Sleuth Demo Script

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_DIR"

echo "=== Java-Sleuth Demo ==="
echo "This script demonstrates Java-Sleuth Phase 1 functionality"
echo

# Check if launcher/agent JARs exist
LAUNCHER_JAR="$(ls -1t "$PROJECT_DIR"/launcher/target/java-sleuth-launcher*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
AGENT_JAR="$(ls -1t "$PROJECT_DIR"/agent/target/java-sleuth-agent-[0-9]*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
CONTAINER_JAR="$(ls -1t "$PROJECT_DIR"/container/target/java-sleuth-container*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
if [ -z "${LAUNCHER_JAR}" ] || [ ! -f "${LAUNCHER_JAR}" ] || [ -z "${AGENT_JAR}" ] || [ ! -f "${AGENT_JAR}" ] || [ -z "${CONTAINER_JAR}" ] || [ ! -f "${CONTAINER_JAR}" ]; then
    # Backward compatibility: legacy single fat-jar (artifactId=java-sleuth)
    LEGACY_JAR="$(ls -1t "$PROJECT_DIR"/core/target/java-sleuth-[0-9]*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    if [ -z "${LEGACY_JAR}" ]; then
        LEGACY_JAR="$(ls -1t "$PROJECT_DIR"/target/java-sleuth-[0-9]*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    fi
    if [ -n "${LEGACY_JAR}" ] && [ -f "${LEGACY_JAR}" ]; then
        LAUNCHER_JAR="${LEGACY_JAR}"
        AGENT_JAR="${LEGACY_JAR}"
        CONTAINER_JAR=""
    else
        echo "Please build the project first with: mvn clean package"
        exit 1
    fi
fi

echo "1. Starting test application..."
bash ./scripts/examples/compile-examples.sh > /dev/null
java -cp target/examples-classes com.javasleuth.test.TestApplication &
TEST_APP_PID=$!

# Wait a moment for the test app to start
sleep 3

echo "2. Test application started with PID: $TEST_APP_PID"
echo "3. Now you can run Java-Sleuth in another terminal:"
echo "   ./sleuth.sh"
echo "4. Select the TestApplication process and try these commands:"
echo "   - dashboard"
echo "   - thread"
echo "   - sc *Test*"
echo "   - sm TestApplication"
echo "   - quit"
echo
echo "Press Ctrl+C to stop the test application"

# Wait for interrupt
trap "echo 'Stopping test application...'; kill $TEST_APP_PID; exit 0" INT
wait $TEST_APP_PID
