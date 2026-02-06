#!/bin/bash

# Java-Sleuth Comprehensive Demo Script
# Demonstrates all phases and features

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_DIR"

echo "=========================="
echo "Java-Sleuth Comprehensive Demo"
echo "=========================="
echo "This demo showcases all three phases of Java-Sleuth:"
echo "Phase 1: JVM Monitoring and Class Analysis"
echo "Phase 2: Method Execution Monitoring"
echo "Phase 3: Hot Code Reload"
echo ""

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Check if JAR exists
JAR_FILE="$(ls -1t "$PROJECT_DIR"/core/target/*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
if [ -z "${JAR_FILE}" ]; then
    JAR_FILE="$(ls -1t "$PROJECT_DIR"/target/*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
fi
if [ -z "${JAR_FILE}" ] || [ ! -f "${JAR_FILE}" ]; then
    echo -e "${RED}Please build the project first with: mvn clean package${NC}"
    exit 1
fi

echo -e "${BLUE}1. Starting Enhanced Test Application...${NC}"
bash ./scripts/examples/compile-examples.sh > /dev/null
java -cp target/examples-classes com.javasleuth.test.EnhancedTestApplication &
TEST_APP_PID=$!

# Wait for the test app to start
sleep 3

echo -e "${GREEN}Enhanced Test Application started with PID: $TEST_APP_PID${NC}"
echo ""

echo -e "${BLUE}2. Demo Instructions:${NC}"
echo "The test application is now running with multiple threads and business logic."
echo "You can now run Java-Sleuth in another terminal with: ./sleuth.sh"
echo ""

echo -e "${YELLOW}Demo Commands to Try:${NC}"
echo ""

echo -e "${GREEN}=== Phase 1 Commands ===${NC}"
echo "1. dashboard                              # JVM real-time statistics"
echo "2. thread                                 # Thread monitoring"
echo "3. thread -d                              # Deadlock detection"
echo "4. sc *Test*                              # Search for test classes"
echo "5. sm EnhancedTestApplication             # Search methods in test class"
echo ""

echo -e "${GREEN}=== Phase 2 Commands ===${NC}"
echo "6. watch *Test* processBusinessTask       # Watch business method execution"
echo "7. watch *BusinessLogic* process*         # Watch business logic methods"
echo "8. trace *Test* calculateFibonacci        # Trace fibonacci calculation"
echo "9. trace *Test* performCalculations       # Trace calculation methods"
echo ""

echo -e "${GREEN}=== Phase 3 Commands ===${NC}"
echo "10. Create a modified version of getGreeting method:"
echo ""
cat << 'EOF'
// Create this file as /tmp/UpdatedGreeting.java
package com.javasleuth.test;
public class EnhancedTestApplication {
    public String getGreeting() {
        return "Hello from Java-Sleuth Test Application v2.0 - HOT RELOADED!";
    }
}
EOF
echo ""
echo "11. mc /tmp/UpdatedGreeting.java -c com.javasleuth.test.EnhancedTestApplication -o /tmp"
echo "12. redefine com.javasleuth.test.EnhancedTestApplication /tmp/com/javasleuth/test/EnhancedTestApplication.class"
echo "13. retransform *Test* --list             # List retransformable classes"
echo ""

echo -e "${YELLOW}Sample Demo Session:${NC}"
echo ""
cat << 'EOF'
sleuth> dashboard
# Shows comprehensive JVM statistics

sleuth> watch *Test* processBusinessTask -n 5
# Watches the business task method execution 5 times
# Shows parameters, return values, and execution time

sleuth> trace *BusinessLogic* processOrder -d 3
# Traces the order processing method call chain
# Shows nested method calls and timing

sleuth> mc /tmp/UpdatedGreeting.java -c com.javasleuth.test.EnhancedTestApplication -o /tmp
# Compiles the modified class

sleuth> redefine com.javasleuth.test.EnhancedTestApplication /tmp/com/javasleuth/test/EnhancedTestApplication.class
# Hot-reloads the class with new implementation

sleuth> quit
EOF
echo ""

echo -e "${BLUE}3. Creating sample file for hot-reload demo...${NC}"
mkdir -p /tmp
cat << 'EOF' > /tmp/UpdatedGreeting.java
package com.javasleuth.test;

public class EnhancedTestApplication {
    public String getGreeting() {
        return "Hello from Java-Sleuth Test Application v2.0 - HOT RELOADED!";
    }
}
EOF

echo -e "${GREEN}Sample file created at /tmp/UpdatedGreeting.java${NC}"
echo ""

echo -e "${BLUE}4. Application Features:${NC}"
echo "The test application includes:"
echo "- Multiple worker threads (WorkerThread, CalculatorThread, ErrorThread)"
echo "- Business logic with method call chains"
echo "- Mathematical calculations (Fibonacci, Prime checking)"
echo "- Error simulation scenarios"
echo "- Methods suitable for watching and tracing"
echo "- Classes that can be hot-reloaded"
echo ""

echo -e "${YELLOW}Advanced Demo Scenarios:${NC}"
echo ""
echo -e "${GREEN}Scenario 1: Performance Analysis${NC}"
echo "1. trace *Test* calculateFibonacci -d 5 -n 10"
echo "2. Observe the recursive call pattern and timing"
echo "3. Identify performance bottlenecks"
echo ""

echo -e "${GREEN}Scenario 2: Error Monitoring${NC}"
echo "1. watch *BusinessLogic* riskyOperation -n 10"
echo "2. Watch for exceptions and error handling"
echo "3. Analyze failure patterns"
echo ""

echo -e "${GREEN}Scenario 3: Hot-Fix Deployment${NC}"
echo "1. Identify a bug in the running application"
echo "2. Fix the source code"
echo "3. Compile with 'mc' command"
echo "4. Deploy with 'redefine' command"
echo "5. Verify the fix without restarting"
echo ""

echo -e "${BLUE}5. Monitoring in Real-time:${NC}"
echo "The application will continue running with output showing:"
echo "- Business task processing every 3 seconds"
echo "- Mathematical calculations every 5 seconds"
echo "- Error scenarios every 8 seconds"
echo ""

echo -e "${RED}To stop the demo:${NC}"
echo "Press Ctrl+C to stop this script and the test application"
echo ""

# Function to cleanup on exit
cleanup() {
    echo ""
    echo -e "${YELLOW}Stopping test application...${NC}"
    kill $TEST_APP_PID 2>/dev/null
    echo -e "${GREEN}Demo cleanup completed.${NC}"
    exit 0
}

# Set trap for cleanup
trap cleanup INT

echo -e "${GREEN}Demo is ready! Start Java-Sleuth in another terminal: ./sleuth.sh${NC}"
echo -e "${BLUE}Press Ctrl+C to stop the demo${NC}"
echo ""

# Keep the script running and show application output
wait $TEST_APP_PID
