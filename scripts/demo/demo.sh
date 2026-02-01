#!/bin/bash

# Java-Sleuth Demo Script

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_DIR"

echo "=== Java-Sleuth Demo ==="
echo "This script demonstrates Java-Sleuth Phase 1 functionality"
echo

# Check if JAR exists
JAR_FILE="$(ls -1t "$PROJECT_DIR"/target/*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
if [ -z "${JAR_FILE}" ] || [ ! -f "${JAR_FILE}" ]; then
    echo "Please build the project first with: mvn clean package"
    exit 1
fi

echo "1. Starting test application..."
java -cp target/classes com.javasleuth.test.TestApplication &
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
