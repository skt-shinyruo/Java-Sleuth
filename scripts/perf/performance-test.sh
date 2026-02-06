#!/bin/bash

# Performance test script for Java-Sleuth
echo "=== Java-Sleuth Performance Testing ==="

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_DIR"

AGENT_JAR="$(ls -1t "$PROJECT_DIR"/target/*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
BASE_JAR="$(ls -1t "$PROJECT_DIR"/target/*.jar 2>/dev/null | grep -v 'jar-with-dependencies' | head -n 1 || true)"

if [[ -z "${AGENT_JAR}" ]] || [[ ! -f "${AGENT_JAR}" ]]; then
    echo "Please build the project first with: mvn clean package"
    exit 1
fi
if [[ -z "${BASE_JAR}" ]] || [[ ! -f "${BASE_JAR}" ]]; then
    echo "Base JAR not found under: $PROJECT_DIR/target/ (expected a non -jar-with-dependencies jar)"
    exit 1
fi

bash ./scripts/examples/compile-examples.sh > /dev/null
EXAMPLES_CLASSES="$PROJECT_DIR/target/examples-classes"

# Test 1: Agent startup time
echo "Test 1: Agent Startup Time"
for i in {1..3}; do
    echo "  Attempt $i:"
    pkill -f "TestApplication" 2>/dev/null || true
    sleep 1

    start_time=$(date +%s%N)
    java -javaagent:"$AGENT_JAR" -cp "$BASE_JAR:$EXAMPLES_CLASSES" com.javasleuth.test.TestApplication > /dev/null 2>&1 &
    APP_PID=$!

    # Wait for agent to be ready
    while ! nc -z localhost 3658 2>/dev/null; do
        sleep 0.1
    done
    end_time=$(date +%s%N)

    startup_time=$(( (end_time - start_time) / 1000000 ))
    echo "    Startup time: ${startup_time}ms"

    kill $APP_PID 2>/dev/null || true
    sleep 1
done

# Start application for command performance tests
java -javaagent:"$AGENT_JAR" -cp "$BASE_JAR:$EXAMPLES_CLASSES" com.javasleuth.test.TestApplication > /dev/null 2>&1 &
APP_PID=$!

# Wait for agent to be ready
while ! nc -z localhost 3658 2>/dev/null; do
    sleep 0.1
done

echo ""
echo "Test 2: Command Response Times"

# Test common commands
commands=("help" "jvm" "memory" "thread" "dashboard")

for cmd in "${commands[@]}"; do
    echo "  Testing $cmd command:"

    total_time=0
    for i in {1..5}; do
        start_time=$(date +%s%N)
        echo "$cmd" | timeout 3 nc localhost 3658 > /dev/null 2>&1
        end_time=$(date +%s%N)

        response_time=$(( (end_time - start_time) / 1000000 ))
        total_time=$((total_time + response_time))
        echo "    Run $i: ${response_time}ms"
    done

    avg_time=$((total_time / 5))
    echo "    Average: ${avg_time}ms"
    echo ""
done

echo "Test 3: Memory Usage"
echo "  Agent process memory usage:"
ps -p $APP_PID -o pid,vsz,rss,pmem,comm | tail -1

echo ""
echo "Test 4: Resource Cleanup"
kill $APP_PID 2>/dev/null || true
sleep 2

if pgrep -f "TestApplication" > /dev/null; then
    echo "  ✗ Process not cleaned up properly"
else
    echo "  ✓ Process cleaned up successfully"
fi

echo ""
echo "=== Performance Test Complete ==="
