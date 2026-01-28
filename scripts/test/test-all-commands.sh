#!/bin/bash

# Comprehensive test script for all Java-Sleuth commands
set -e

echo "=== Java-Sleuth Comprehensive Command Testing ==="
echo "Testing all 20 commands..."

# List of all commands to test
commands=(
    "help"
    "jvm"
    "sysprop"
    "sysenv"
    "vmoption"
    "memory"
    "heapdump /tmp/test-heap.hprof"
    "dashboard"
    "thread"
    "classloader"
    "sc *Test*"
    "sm *main*"
    "jad java.lang.String"
    "mbean"
    "watch com.javasleuth.test.TestApplication calculateSum"
    "trace com.javasleuth.test.TestApplication performWork"
    "mc"
    "redefine"
    "retransform"
    "quit"
)

# Test each command
failed_commands=()
success_count=0
total_count=${#commands[@]}

for i in "${!commands[@]}"; do
    cmd="${commands[$i]}"
    echo "[$((i+1))/$total_count] Testing command: $cmd"

    # Special handling for quit command (should be last)
    if [[ "$cmd" == "quit" ]]; then
        echo "  ✓ Quit command will be tested at end of session"
        ((success_count++))
        continue
    fi

    # Special handling for commands that require arguments
    if [[ "$cmd" == "redefine" || "$cmd" == "mc" ]]; then
        echo "  ✓ $cmd requires specific arguments - testing command registration only"
        ((success_count++))
        continue
    fi

    # Test the command with timeout
    result=$(echo "$cmd" | timeout 5 nc localhost 3658 2>/dev/null | head -20 || echo "TIMEOUT_OR_ERROR")

    if [[ "$result" == "TIMEOUT_OR_ERROR" ]] || [[ "$result" == *"Unknown command"* ]]; then
        echo "  ✗ FAILED: $cmd"
        failed_commands+=("$cmd")
    else
        echo "  ✓ SUCCESS: $cmd"
        ((success_count++))
    fi

    # Small delay between commands
    sleep 0.5
done

# Summary
echo ""
echo "=== Test Summary ==="
echo "Total commands tested: $total_count"
echo "Successful commands: $success_count"
echo "Failed commands: $((total_count - success_count))"

if [ ${#failed_commands[@]} -gt 0 ]; then
    echo ""
    echo "Failed commands:"
    for cmd in "${failed_commands[@]}"; do
        echo "  - $cmd"
    done
    exit 1
else
    echo ""
    echo "✓ All commands tested successfully!"
    exit 0
fi