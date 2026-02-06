#!/bin/bash

# Java-Sleuth Performance Benchmark Script
# This script performs comprehensive performance testing and benchmarking

set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
BENCHMARK_DIR="$SCRIPT_DIR/benchmark-results"
TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
REPORT_FILE="$BENCHMARK_DIR/benchmark_report_$TIMESTAMP.md"
JAR_FILE=""
TEST_PORT=3658
CONCURRENT_CLIENTS=10
TEST_DURATION=60

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() {
    echo -e "${GREEN}[$(date +'%H:%M:%S')] $1${NC}"
}

warn() {
    echo -e "${YELLOW}[$(date +'%H:%M:%S')] WARNING: $1${NC}"
}

error() {
    echo -e "${RED}[$(date +'%H:%M:%S')] ERROR: $1${NC}"
    exit 1
}

info() {
    echo -e "${BLUE}[$(date +'%H:%M:%S')] INFO: $1${NC}"
}

# Create benchmark directory
setup_benchmark() {
    log "Setting up benchmark environment..."
    mkdir -p "$BENCHMARK_DIR"

    # Check if JAR exists
    JAR_FILE="$(ls -1t "$PROJECT_DIR"/core/target/*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    if [[ -z "$JAR_FILE" ]] || [[ ! -f "$JAR_FILE" ]]; then
        JAR_FILE="$(ls -1t "$PROJECT_DIR"/target/*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    fi
    if [[ -z "$JAR_FILE" ]] || [[ ! -f "$JAR_FILE" ]]; then
        log "Building project..."
        (cd "$PROJECT_DIR" && mvn clean package -DskipTests)
        JAR_FILE="$(ls -1t "$PROJECT_DIR"/core/target/*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
        if [[ -z "$JAR_FILE" ]] || [[ ! -f "$JAR_FILE" ]]; then
            JAR_FILE="$(ls -1t "$PROJECT_DIR"/target/*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
        fi
        if [[ -z "$JAR_FILE" ]] || [[ ! -f "$JAR_FILE" ]]; then
            error "Failed to build agent JAR under: $PROJECT_DIR/core/target/ (or legacy $PROJECT_DIR/target/)"
        fi
    fi
}

# Start Java-Sleuth for testing
start_sleuth() {
    log "Starting Java-Sleuth for benchmarking..."

    # Kill any existing instances
    pkill -f "java-sleuth.*jar" 2>/dev/null || true
    sleep 2

    # Start with optimized JVM settings for performance testing
    java -Xms1g -Xmx2g \
         -XX:+UseG1GC \
         -XX:MaxGCPauseMillis=100 \
         -XX:+UseCompressedOops \
         -javaagent:"$JAR_FILE" \
         -Dsleuth.server.port=$TEST_PORT \
         -Dsleuth.performance.thread.pool.core=16 \
         -Dsleuth.performance.thread.pool.max=32 \
         -Dsleuth.performance.cache.ttl=10000 \
         TestApp &

    local sleuth_pid=$!
    echo $sleuth_pid > /tmp/sleuth_benchmark.pid

    # Wait for startup
    log "Waiting for Java-Sleuth to start..."
    local retries=0
    while ! nc -z localhost $TEST_PORT && [[ $retries -lt 30 ]]; do
        sleep 1
        ((retries++))
    done

    if ! nc -z localhost $TEST_PORT; then
        error "Java-Sleuth failed to start on port $TEST_PORT"
    fi

    log "Java-Sleuth started successfully (PID: $sleuth_pid)"
}

# Stop Java-Sleuth
stop_sleuth() {
    log "Stopping Java-Sleuth..."
    if [[ -f /tmp/sleuth_benchmark.pid ]]; then
        local pid=$(cat /tmp/sleuth_benchmark.pid)
        kill $pid 2>/dev/null || true
        rm -f /tmp/sleuth_benchmark.pid
    fi
    pkill -f "java-sleuth.*jar" 2>/dev/null || true
    sleep 2
}

# Basic connectivity test
test_connectivity() {
    log "Testing basic connectivity..."

    local start_time=$(date +%s%N)
    echo "help" | timeout 5 nc localhost $TEST_PORT > /tmp/help_response.txt
    local end_time=$(date +%s%N)
    local response_time=$(( (end_time - start_time) / 1000000 ))

    if grep -q "Available commands" /tmp/help_response.txt; then
        log "✅ Connectivity test passed (${response_time}ms)"
        echo "connectivity_test_ms=$response_time" >> "$BENCHMARK_DIR/results.txt"
    else
        error "❌ Connectivity test failed"
    fi
}

# Memory usage test
test_memory_usage() {
    log "Testing memory usage..."

    local pid=$(cat /tmp/sleuth_benchmark.pid)
    local initial_memory=$(ps -p $pid -o rss= | tr -d ' ')

    # Run multiple commands to load caches
    for i in {1..100}; do
        echo "dashboard" | nc localhost $TEST_PORT > /dev/null 2>&1 &
        echo "thread" | nc localhost $TEST_PORT > /dev/null 2>&1 &
        echo "memory" | nc localhost $TEST_PORT > /dev/null 2>&1 &
    done
    wait

    sleep 5
    local peak_memory=$(ps -p $pid -o rss= | tr -d ' ')
    local memory_growth=$((peak_memory - initial_memory))

    log "Memory usage: Initial=${initial_memory}KB, Peak=${peak_memory}KB, Growth=${memory_growth}KB"
    echo "initial_memory_kb=$initial_memory" >> "$BENCHMARK_DIR/results.txt"
    echo "peak_memory_kb=$peak_memory" >> "$BENCHMARK_DIR/results.txt"
    echo "memory_growth_kb=$memory_growth" >> "$BENCHMARK_DIR/results.txt"

    # Check for memory leaks
    if [[ $memory_growth -gt 50000 ]]; then # 50MB growth threshold
        warn "Potential memory leak detected: ${memory_growth}KB growth"
    else
        log "✅ Memory usage acceptable"
    fi
}

# Command performance test
test_command_performance() {
    log "Testing command performance..."

    local commands=("help" "dashboard" "thread" "memory" "jvm" "health" "status" "metrics")

    for cmd in "${commands[@]}"; do
        log "Testing command: $cmd"

        local total_time=0
        local successful_requests=0
        local failed_requests=0

        for i in {1..50}; do
            local start_time=$(date +%s%N)
            if echo "$cmd" | timeout 10 nc localhost $TEST_PORT > /dev/null 2>&1; then
                local end_time=$(date +%s%N)
                local request_time=$(( (end_time - start_time) / 1000000 ))
                total_time=$((total_time + request_time))
                ((successful_requests++))
            else
                ((failed_requests++))
            fi
        done

        if [[ $successful_requests -gt 0 ]]; then
            local avg_time=$((total_time / successful_requests))
            log "Command $cmd: ${avg_time}ms average (${successful_requests} success, ${failed_requests} failed)"
            echo "${cmd}_avg_ms=$avg_time" >> "$BENCHMARK_DIR/results.txt"
            echo "${cmd}_success=$successful_requests" >> "$BENCHMARK_DIR/results.txt"
            echo "${cmd}_failed=$failed_requests" >> "$BENCHMARK_DIR/results.txt"
        else
            warn "Command $cmd: All requests failed"
        fi
    done
}

# Concurrent load test
test_concurrent_load() {
    log "Testing concurrent load with $CONCURRENT_CLIENTS clients..."

    local start_time=$(date +%s)
    local pids=()

    # Start concurrent clients
    for i in $(seq 1 $CONCURRENT_CLIENTS); do
        (
            local client_requests=0
            local client_errors=0
            local client_start=$(date +%s)

            while [[ $(($(date +%s) - client_start)) -lt $TEST_DURATION ]]; do
                local cmd="dashboard"
                if echo "$cmd" | timeout 5 nc localhost $TEST_PORT > /dev/null 2>&1; then
                    ((client_requests++))
                else
                    ((client_errors++))
                fi
            done

            echo "client_${i}_requests=$client_requests" >> "$BENCHMARK_DIR/concurrent_results.txt"
            echo "client_${i}_errors=$client_errors" >> "$BENCHMARK_DIR/concurrent_results.txt"
        ) &
        pids+=($!)
    done

    log "Load test running for ${TEST_DURATION} seconds..."

    # Wait for all clients to finish
    for pid in "${pids[@]}"; do
        wait $pid
    done

    local end_time=$(date +%s)
    local actual_duration=$((end_time - start_time))

    # Calculate totals
    local total_requests=0
    local total_errors=0

    if [[ -f "$BENCHMARK_DIR/concurrent_results.txt" ]]; then
        while IFS= read -r line; do
            if [[ $line =~ _requests=([0-9]+) ]]; then
                total_requests=$((total_requests + ${BASH_REMATCH[1]}))
            elif [[ $line =~ _errors=([0-9]+) ]]; then
                total_errors=$((total_errors + ${BASH_REMATCH[1]}))
            fi
        done < "$BENCHMARK_DIR/concurrent_results.txt"
    fi

    local throughput=$((total_requests / actual_duration))
    local error_rate=0
    if [[ $total_requests -gt 0 ]]; then
        error_rate=$(( (total_errors * 100) / (total_requests + total_errors) ))
    fi

    log "Concurrent load test results:"
    log "  Duration: ${actual_duration}s"
    log "  Total requests: $total_requests"
    log "  Total errors: $total_errors"
    log "  Throughput: ${throughput} req/s"
    log "  Error rate: ${error_rate}%"

    echo "concurrent_duration_s=$actual_duration" >> "$BENCHMARK_DIR/results.txt"
    echo "concurrent_total_requests=$total_requests" >> "$BENCHMARK_DIR/results.txt"
    echo "concurrent_total_errors=$total_errors" >> "$BENCHMARK_DIR/results.txt"
    echo "concurrent_throughput_rps=$throughput" >> "$BENCHMARK_DIR/results.txt"
    echo "concurrent_error_rate_percent=$error_rate" >> "$BENCHMARK_DIR/results.txt"
}

# Cache performance test
test_cache_performance() {
    log "Testing cache performance..."

    # Test cache miss performance (first request)
    local start_time=$(date +%s%N)
    echo "sc java.lang.String" | nc localhost $TEST_PORT > /dev/null
    local end_time=$(date +%s%N)
    local cache_miss_time=$(( (end_time - start_time) / 1000000 ))

    # Test cache hit performance (subsequent requests)
    local total_hit_time=0
    for i in {1..10}; do
        start_time=$(date +%s%N)
        echo "sc java.lang.String" | nc localhost $TEST_PORT > /dev/null
        end_time=$(date +%s%N)
        local hit_time=$(( (end_time - start_time) / 1000000 ))
        total_hit_time=$((total_hit_time + hit_time))
    done

    local avg_hit_time=$((total_hit_time / 10))
    local cache_improvement=$((cache_miss_time - avg_hit_time))
    local improvement_percent=$(( (cache_improvement * 100) / cache_miss_time ))

    log "Cache performance:"
    log "  Cache miss: ${cache_miss_time}ms"
    log "  Cache hit average: ${avg_hit_time}ms"
    log "  Improvement: ${improvement_percent}%"

    echo "cache_miss_ms=$cache_miss_time" >> "$BENCHMARK_DIR/results.txt"
    echo "cache_hit_avg_ms=$avg_hit_time" >> "$BENCHMARK_DIR/results.txt"
    echo "cache_improvement_percent=$improvement_percent" >> "$BENCHMARK_DIR/results.txt"
}

# GC performance test
test_gc_performance() {
    log "Testing GC performance impact..."

    local pid=$(cat /tmp/sleuth_benchmark.pid)

    # Get initial GC stats
    local initial_gc_time=$(jstat -gc $pid | tail -1 | awk '{print $9+$11}')

    # Generate load to trigger GC
    for i in {1..200}; do
        echo "dashboard" | nc localhost $TEST_PORT > /dev/null &
        echo "thread" | nc localhost $TEST_PORT > /dev/null &
        echo "memory" | nc localhost $TEST_PORT > /dev/null &
    done
    wait

    sleep 5

    # Get final GC stats
    local final_gc_time=$(jstat -gc $pid | tail -1 | awk '{print $9+$11}')
    local gc_time_diff=$(echo "$final_gc_time - $initial_gc_time" | bc -l)

    log "GC performance: ${gc_time_diff}s total GC time during test"
    echo "gc_time_during_test_s=$gc_time_diff" >> "$BENCHMARK_DIR/results.txt"
}

# Generate comprehensive report
generate_report() {
    log "Generating benchmark report..."

    local system_info="$(uname -a)"
    local java_version="$(java -version 2>&1 | head -1)"
    local cpu_info="$(grep 'model name' /proc/cpuinfo | head -1 | cut -d: -f2 | xargs)"
    local memory_info="$(free -h | grep Mem | awk '{print $2}')"

    cat > "$REPORT_FILE" << EOF
# Java-Sleuth Performance Benchmark Report

**Date:** $(date)
**System:** $system_info
**Java:** $java_version
**CPU:** $cpu_info
**Memory:** $memory_info

## Test Configuration

- **Concurrent Clients:** $CONCURRENT_CLIENTS
- **Test Duration:** ${TEST_DURATION}s
- **Test Port:** $TEST_PORT

## Results Summary

EOF

    if [[ -f "$BENCHMARK_DIR/results.txt" ]]; then
        while IFS='=' read -r key value; do
            case $key in
                connectivity_test_ms)
                    echo "- **Connectivity Response Time:** ${value}ms" >> "$REPORT_FILE"
                    ;;
                initial_memory_kb)
                    echo "- **Initial Memory Usage:** $((value / 1024))MB" >> "$REPORT_FILE"
                    ;;
                peak_memory_kb)
                    echo "- **Peak Memory Usage:** $((value / 1024))MB" >> "$REPORT_FILE"
                    ;;
                memory_growth_kb)
                    echo "- **Memory Growth:** $((value / 1024))MB" >> "$REPORT_FILE"
                    ;;
                concurrent_throughput_rps)
                    echo "- **Concurrent Throughput:** ${value} req/s" >> "$REPORT_FILE"
                    ;;
                concurrent_error_rate_percent)
                    echo "- **Error Rate:** ${value}%" >> "$REPORT_FILE"
                    ;;
                cache_improvement_percent)
                    echo "- **Cache Performance Improvement:** ${value}%" >> "$REPORT_FILE"
                    ;;
            esac
        done < "$BENCHMARK_DIR/results.txt"
    fi

    cat >> "$REPORT_FILE" << EOF

## Detailed Command Performance

| Command | Avg Response Time | Success Rate |
|---------|------------------|--------------|
EOF

    if [[ -f "$BENCHMARK_DIR/results.txt" ]]; then
        local commands=("help" "dashboard" "thread" "memory" "jvm" "health" "status" "metrics")
        for cmd in "${commands[@]}"; do
            local avg_time=$(grep "${cmd}_avg_ms=" "$BENCHMARK_DIR/results.txt" | cut -d= -f2)
            local success=$(grep "${cmd}_success=" "$BENCHMARK_DIR/results.txt" | cut -d= -f2)
            local failed=$(grep "${cmd}_failed=" "$BENCHMARK_DIR/results.txt" | cut -d= -f2)

            if [[ -n "$avg_time" && -n "$success" && -n "$failed" ]]; then
                local total=$((success + failed))
                local success_rate=$((success * 100 / total))
                echo "| $cmd | ${avg_time}ms | ${success_rate}% |" >> "$REPORT_FILE"
            fi
        done
    fi

    cat >> "$REPORT_FILE" << EOF

## Performance Analysis

### Memory Usage
$(if grep -q "memory_growth_kb" "$BENCHMARK_DIR/results.txt"; then
    local growth=$(grep "memory_growth_kb=" "$BENCHMARK_DIR/results.txt" | cut -d= -f2)
    if [[ $growth -lt 10000 ]]; then
        echo "✅ **GOOD**: Memory growth is minimal ($((growth / 1024))MB)"
    elif [[ $growth -lt 50000 ]]; then
        echo "⚠️ **ACCEPTABLE**: Moderate memory growth ($((growth / 1024))MB)"
    else
        echo "❌ **CONCERN**: High memory growth ($((growth / 1024))MB) - investigate potential leaks"
    fi
else
    echo "No memory data available"
fi)

### Throughput
$(if grep -q "concurrent_throughput_rps" "$BENCHMARK_DIR/results.txt"; then
    local throughput=$(grep "concurrent_throughput_rps=" "$BENCHMARK_DIR/results.txt" | cut -d= -f2)
    if [[ $throughput -gt 100 ]]; then
        echo "✅ **EXCELLENT**: High throughput (${throughput} req/s)"
    elif [[ $throughput -gt 50 ]]; then
        echo "✅ **GOOD**: Adequate throughput (${throughput} req/s)"
    else
        echo "⚠️ **NEEDS IMPROVEMENT**: Low throughput (${throughput} req/s)"
    fi
else
    echo "No throughput data available"
fi)

### Error Rate
$(if grep -q "concurrent_error_rate_percent" "$BENCHMARK_DIR/results.txt"; then
    local error_rate=$(grep "concurrent_error_rate_percent=" "$BENCHMARK_DIR/results.txt" | cut -d= -f2)
    if [[ $error_rate -eq 0 ]]; then
        echo "✅ **PERFECT**: No errors detected"
    elif [[ $error_rate -lt 5 ]]; then
        echo "✅ **GOOD**: Low error rate (${error_rate}%)"
    else
        echo "❌ **CONCERN**: High error rate (${error_rate}%)"
    fi
else
    echo "No error rate data available"
fi)

### Cache Performance
$(if grep -q "cache_improvement_percent" "$BENCHMARK_DIR/results.txt"; then
    local improvement=$(grep "cache_improvement_percent=" "$BENCHMARK_DIR/results.txt" | cut -d= -f2)
    if [[ $improvement -gt 50 ]]; then
        echo "✅ **EXCELLENT**: Cache provides ${improvement}% performance improvement"
    elif [[ $improvement -gt 20 ]]; then
        echo "✅ **GOOD**: Cache provides ${improvement}% performance improvement"
    else
        echo "⚠️ **SUBOPTIMAL**: Cache only provides ${improvement}% improvement"
    fi
else
    echo "No cache performance data available"
fi)

## Recommendations

- Monitor memory usage in production environments
- Consider adjusting cache TTL based on usage patterns
- Implement connection pooling for high-load scenarios
- Set up alerting for error rates above 5%
- Regular performance testing as part of CI/CD pipeline

## Raw Data Files

- Results: \`benchmark-results/results.txt\`
- Concurrent test data: \`benchmark-results/concurrent_results.txt\`

---
*Generated by Java-Sleuth Performance Benchmark Suite*
EOF

    log "📊 Benchmark report generated: $REPORT_FILE"
}

# Cleanup function
cleanup() {
    log "Cleaning up..."
    stop_sleuth
    rm -f /tmp/help_response.txt
    rm -f "$BENCHMARK_DIR/concurrent_results.txt"
}

# Main benchmark function
main() {
    echo "🚀 Java-Sleuth Performance Benchmark Suite"
    echo "==========================================="
    echo

    # Setup trap for cleanup
    trap cleanup EXIT

    setup_benchmark
    start_sleuth

    # Run all performance tests
    test_connectivity
    test_memory_usage
    test_command_performance
    test_concurrent_load
    test_cache_performance
    test_gc_performance

    generate_report

    log "🎉 Benchmark complete!"
    log "📊 Report available at: $REPORT_FILE"
    log "📁 Results directory: $BENCHMARK_DIR"
}

# Create a simple test application for benchmarking
create_test_app() {
    cat > TestApp.java << 'EOF'
public class TestApp {
    public static void main(String[] args) {
        System.out.println("Test application for Java-Sleuth benchmarking");

        // Keep the application running
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            System.out.println("Test application interrupted");
        }
    }
}
EOF

    javac TestApp.java
}

# Check dependencies
check_dependencies() {
    local missing_deps=()

    if ! command -v java &> /dev/null; then
        missing_deps+=("java")
    fi

    if ! command -v mvn &> /dev/null; then
        missing_deps+=("maven")
    fi

    if ! command -v nc &> /dev/null; then
        missing_deps+=("netcat")
    fi

    if ! command -v bc &> /dev/null; then
        missing_deps+=("bc")
    fi

    if [[ ${#missing_deps[@]} -gt 0 ]]; then
        error "Missing dependencies: ${missing_deps[*]}"
    fi
}

# Entry point
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    check_dependencies
    create_test_app
    main "$@"
fi
