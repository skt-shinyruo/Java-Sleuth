#!/bin/bash

# Java-Sleuth Production Deployment Script
# This script handles production deployment with comprehensive safety checks

set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_NAME="java-sleuth"
VERSION="1.0.0"
JAR_NAME="${PROJECT_NAME}-${VERSION}-jar-with-dependencies.jar"
AGENT_JAR="target/${JAR_NAME}"
DEPLOY_DIR="/opt/java-sleuth"
CONFIG_DIR="${DEPLOY_DIR}/config"
LOGS_DIR="${DEPLOY_DIR}/logs"
BACKUP_DIR="${DEPLOY_DIR}/backup"
SERVICE_NAME="java-sleuth"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging
log() {
    echo -e "${GREEN}[$(date +'%Y-%m-%d %H:%M:%S')] $1${NC}"
}

warn() {
    echo -e "${YELLOW}[$(date +'%Y-%m-%d %H:%M:%S')] WARNING: $1${NC}"
}

error() {
    echo -e "${RED}[$(date +'%Y-%m-%d %H:%M:%S')] ERROR: $1${NC}"
    exit 1
}

info() {
    echo -e "${BLUE}[$(date +'%Y-%m-%d %H:%M:%S')] INFO: $1${NC}"
}

# Check if running as root
check_root() {
    if [[ $EUID -eq 0 ]]; then
        warn "Running as root. Consider creating a dedicated user for Java-Sleuth."
    fi
}

# Check prerequisites
check_prerequisites() {
    log "Checking prerequisites..."

    # Check Java
    if ! command -v java &> /dev/null; then
        error "Java is not installed or not in PATH"
    fi

    local java_version=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
    log "Java version: $java_version"

    # Check if systemctl is available
    if command -v systemctl &> /dev/null; then
        info "systemd is available for service management"
    else
        warn "systemd not available - manual service management required"
    fi

    # Check if firewall is running
    if command -v ufw &> /dev/null && ufw status | grep -q "Status: active"; then
        warn "UFW firewall is active. You may need to open port 3658"
    fi

    log "Prerequisites check complete"
}

# Create directory structure
create_directories() {
    log "Creating directory structure..."

    sudo mkdir -p "$DEPLOY_DIR" "$CONFIG_DIR" "$LOGS_DIR" "$BACKUP_DIR"
    sudo mkdir -p "${DEPLOY_DIR}/bin" "${DEPLOY_DIR}/lib"

    # Set appropriate permissions
    sudo chown -R $USER:$USER "$DEPLOY_DIR"
    sudo chmod 755 "$DEPLOY_DIR"
    sudo chmod 755 "$CONFIG_DIR" "$LOGS_DIR" "$BACKUP_DIR"

    log "Directory structure created"
}

# Build the project
build_project() {
    log "Building project..."

    if [[ ! -f "pom.xml" ]]; then
        error "pom.xml not found. Please run this script from the project root."
    fi

    # Clean and build
    mvn clean package -DskipTests

    if [[ ! -f "$AGENT_JAR" ]]; then
        error "Build failed - JAR file not found: $AGENT_JAR"
    fi

    log "Project built successfully"
}

# Deploy files
deploy_files() {
    log "Deploying files..."

    # Backup existing installation
    if [[ -f "${DEPLOY_DIR}/lib/${JAR_NAME}" ]]; then
        local backup_name="backup-$(date +%Y%m%d_%H%M%S)"
        mkdir -p "${BACKUP_DIR}/${backup_name}"
        cp "${DEPLOY_DIR}/lib/${JAR_NAME}" "${BACKUP_DIR}/${backup_name}/"
        log "Previous installation backed up to ${BACKUP_DIR}/${backup_name}"
    fi

    # Copy JAR file
    cp "$AGENT_JAR" "${DEPLOY_DIR}/lib/"

    # Copy startup scripts
    cp sleuth.sh "${DEPLOY_DIR}/bin/"
    chmod +x "${DEPLOY_DIR}/bin/sleuth.sh"

    # Copy configuration files
    create_config_files

    log "Files deployed successfully"
}

# Create configuration files
create_config_files() {
    log "Creating configuration files..."

    # Create default configuration
    cat > "${CONFIG_DIR}/sleuth.properties" << 'EOF'
# Java-Sleuth Production Configuration

# Server configuration
server.port=3658
server.max.connections=50
server.connection.timeout=30000
server.socket.timeout=2000

# Performance configuration
performance.cache.ttl=10000
performance.thread.pool.core=8
performance.thread.pool.max=32
performance.command.timeout=120000

# Security configuration
security.input.validation=true
security.audit.logging=true
security.max.command.length=2000
security.allowed.commands=*

# Monitoring configuration
monitoring.metrics.enabled=true
monitoring.health.checks=true
monitoring.cache.cleanup.interval=300000
monitoring.jmx.enabled=true

# Logging configuration
logging.level=INFO
logging.audit.enabled=true
logging.performance.enabled=true
EOF

    # Create JVM configuration
    cat > "${CONFIG_DIR}/jvm.conf" << 'EOF'
# JVM Configuration for Java-Sleuth Production

# Memory settings
-Xms512m
-Xmx2g
-XX:MetaspaceSize=128m
-XX:MaxMetaspaceSize=512m

# Garbage Collection (G1GC for low latency)
-XX:+UseG1GC
-XX:G1HeapRegionSize=16m
-XX:MaxGCPauseMillis=200
-XX:+G1UseAdaptiveIHOP
-XX:G1HeapWastePercent=5

# Performance tuning
-XX:+UseLargePages
-XX:+UseCompressedOops
-XX:+UseCompressedClassPointers
-XX:+OptimizeStringConcat

# Security
-Djava.security.egd=file:/dev/./urandom
-Dfile.encoding=UTF-8

# JMX monitoring
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=9999
-Dcom.sun.management.jmxremote.authenticate=false
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.local.only=false

# Logging
-Djava.util.logging.config.file=${CONFIG_DIR}/logging.properties

# Application specific
-Dsleuth.config.dir=${CONFIG_DIR}
-Dsleuth.logs.dir=${LOGS_DIR}
EOF

    # Create logging configuration
    cat > "${CONFIG_DIR}/logging.properties" << 'EOF'
# Java-Sleuth Logging Configuration

# Root logger
.level=INFO
handlers=java.util.logging.FileHandler, java.util.logging.ConsoleHandler

# File handler
java.util.logging.FileHandler.pattern=${sleuth.logs.dir}/sleuth-%g.log
java.util.logging.FileHandler.limit=10485760
java.util.logging.FileHandler.count=10
java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
java.util.logging.FileHandler.level=INFO

# Console handler
java.util.logging.ConsoleHandler.level=INFO
java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter

# Application loggers
com.javasleuth.level=INFO
com.javasleuth.security.level=DEBUG
com.javasleuth.monitoring.level=INFO

# Third-party loggers
org.ow2.asm.level=WARNING
com.fasterxml.jackson.level=WARNING
EOF

    # Create systemd service file
    if command -v systemctl &> /dev/null; then
        create_systemd_service
    fi

    log "Configuration files created"
}

# Create systemd service
create_systemd_service() {
    log "Creating systemd service..."

    cat > "/tmp/${SERVICE_NAME}.service" << EOF
[Unit]
Description=Java-Sleuth Diagnostic Agent
Documentation=https://github.com/your-org/java-sleuth
After=network.target
Wants=network.target

[Service]
Type=simple
User=$USER
Group=$USER
WorkingDirectory=${DEPLOY_DIR}
Environment=JAVA_HOME=/usr/lib/jvm/default
Environment=SLEUTH_HOME=${DEPLOY_DIR}
Environment=SLEUTH_CONFIG=${CONFIG_DIR}
ExecStart=/bin/bash ${DEPLOY_DIR}/bin/sleuth.sh
ExecStop=/bin/kill -TERM \$MAINPID
ExecReload=/bin/kill -HUP \$MAINPID
Restart=always
RestartSec=30
StandardOutput=journal
StandardError=journal
SyslogIdentifier=java-sleuth

# Security settings
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=${DEPLOY_DIR} ${LOGS_DIR}

# Resource limits
LimitNOFILE=65536
LimitNPROC=32768

[Install]
WantedBy=multi-user.target
EOF

    sudo mv "/tmp/${SERVICE_NAME}.service" "/etc/systemd/system/"
    sudo systemctl daemon-reload

    log "Systemd service created"
}

# Setup monitoring
setup_monitoring() {
    log "Setting up monitoring..."

    # Create monitoring script
    cat > "${DEPLOY_DIR}/bin/monitor.sh" << 'EOF'
#!/bin/bash
# Java-Sleuth Monitoring Script

SLEUTH_HOME="${SLEUTH_HOME:-/opt/java-sleuth}"
LOGS_DIR="${SLEUTH_HOME}/logs"

# Function to check if Java-Sleuth is running
check_sleuth() {
    if pgrep -f "java-sleuth.*jar" > /dev/null; then
        echo "✅ Java-Sleuth is running"
        return 0
    else
        echo "❌ Java-Sleuth is not running"
        return 1
    fi
}

# Function to check health endpoint
check_health() {
    local health_status
    if health_status=$(timeout 10 telnet localhost 3658 2>/dev/null | echo "health" | timeout 5 cat); then
        echo "✅ Health check passed"
        return 0
    else
        echo "❌ Health check failed"
        return 1
    fi
}

# Function to check disk space
check_disk_space() {
    local usage
    usage=$(df "$LOGS_DIR" | awk 'NR==2 {print $5}' | sed 's/%//')
    if [[ $usage -lt 80 ]]; then
        echo "✅ Disk space OK (${usage}% used)"
        return 0
    else
        echo "⚠️ Disk space warning (${usage}% used)"
        return 1
    fi
}

# Function to check memory usage
check_memory() {
    local pid
    if pid=$(pgrep -f "java-sleuth.*jar"); then
        local mem_usage
        mem_usage=$(ps -p "$pid" -o %mem --no-headers | tr -d ' ')
        echo "📊 Memory usage: ${mem_usage}%"
        if (( $(echo "$mem_usage > 80" | bc -l) )); then
            echo "⚠️ High memory usage detected"
            return 1
        else
            return 0
        fi
    else
        echo "❌ Cannot check memory - process not found"
        return 1
    fi
}

# Main monitoring
echo "=== Java-Sleuth Monitoring Report ==="
echo "Timestamp: $(date)"
echo

exit_code=0

check_sleuth || exit_code=1
check_health || exit_code=1
check_disk_space || exit_code=1
check_memory || exit_code=1

echo
if [[ $exit_code -eq 0 ]]; then
    echo "🎉 All checks passed"
else
    echo "⚠️ Some checks failed"
fi

exit $exit_code
EOF

    chmod +x "${DEPLOY_DIR}/bin/monitor.sh"

    log "Monitoring setup complete"
}

# Create production startup script
create_startup_script() {
    log "Creating production startup script..."

    cat > "${DEPLOY_DIR}/bin/sleuth-production.sh" << EOF
#!/bin/bash
# Java-Sleuth Production Startup Script

set -euo pipefail

# Configuration
SLEUTH_HOME="${DEPLOY_DIR}"
CONFIG_DIR="${CONFIG_DIR}"
LOGS_DIR="${LOGS_DIR}"
JAR_FILE="\${SLEUTH_HOME}/lib/${JAR_NAME}"
PID_FILE="\${SLEUTH_HOME}/sleuth.pid"

# Load JVM configuration
JVM_OPTS=""
if [[ -f "\${CONFIG_DIR}/jvm.conf" ]]; then
    while IFS= read -r line; do
        # Skip comments and empty lines
        [[ \$line =~ ^[[:space:]]*# ]] && continue
        [[ -z "\${line// }" ]] && continue
        JVM_OPTS="\$JVM_OPTS \$line"
    done < "\${CONFIG_DIR}/jvm.conf"
fi

# Replace placeholders in JVM options
JVM_OPTS="\${JVM_OPTS//\\\${CONFIG_DIR}/\$CONFIG_DIR}"
JVM_OPTS="\${JVM_OPTS//\\\${LOGS_DIR}/\$LOGS_DIR}"

# Function to start Java-Sleuth
start_sleuth() {
    echo "🚀 Starting Java-Sleuth in production mode..."

    # Check if already running
    if [[ -f "\$PID_FILE" ]] && kill -0 "\$(cat "\$PID_FILE")" 2>/dev/null; then
        echo "❌ Java-Sleuth is already running (PID: \$(cat "\$PID_FILE"))"
        exit 1
    fi

    # Create logs directory
    mkdir -p "\$LOGS_DIR"

    # Start the application
    nohup java \$JVM_OPTS \\
        -javaagent:"\$JAR_FILE" \\
        -Dsleuth.config.file="\${CONFIG_DIR}/sleuth.properties" \\
        -cp "\$JAR_FILE" \\
        com.javasleuth.agent.SleuthAgent \\
        > "\${LOGS_DIR}/sleuth.out" 2>&1 &

    local pid=\$!
    echo \$pid > "\$PID_FILE"

    # Wait a moment and check if process is still running
    sleep 3
    if kill -0 \$pid 2>/dev/null; then
        echo "✅ Java-Sleuth started successfully (PID: \$pid)"
        echo "📊 Monitor logs: tail -f \${LOGS_DIR}/sleuth.out"
        echo "🌐 Server will be available on port 3658"
    else
        echo "❌ Failed to start Java-Sleuth"
        rm -f "\$PID_FILE"
        exit 1
    fi
}

# Function to stop Java-Sleuth
stop_sleuth() {
    echo "🛑 Stopping Java-Sleuth..."

    if [[ ! -f "\$PID_FILE" ]]; then
        echo "❌ PID file not found. Java-Sleuth may not be running."
        exit 1
    fi

    local pid=\$(cat "\$PID_FILE")
    if ! kill -0 "\$pid" 2>/dev/null; then
        echo "❌ Process \$pid is not running"
        rm -f "\$PID_FILE"
        exit 1
    fi

    # Graceful shutdown
    kill -TERM "\$pid"

    # Wait for graceful shutdown
    local count=0
    while kill -0 "\$pid" 2>/dev/null && [[ \$count -lt 30 ]]; do
        sleep 1
        ((count++))
    done

    if kill -0 "\$pid" 2>/dev/null; then
        echo "⚠️ Graceful shutdown failed, forcing termination..."
        kill -KILL "\$pid"
        sleep 2
    fi

    rm -f "\$PID_FILE"
    echo "✅ Java-Sleuth stopped successfully"
}

# Function to check status
status_sleuth() {
    if [[ -f "\$PID_FILE" ]] && kill -0 "\$(cat "\$PID_FILE")" 2>/dev/null; then
        local pid=\$(cat "\$PID_FILE")
        echo "✅ Java-Sleuth is running (PID: \$pid)"

        # Show some basic stats
        local mem_usage=\$(ps -p "\$pid" -o %mem --no-headers | tr -d ' ')
        local cpu_usage=\$(ps -p "\$pid" -o %cpu --no-headers | tr -d ' ')
        echo "📊 Memory: \${mem_usage}%, CPU: \${cpu_usage}%"
    else
        echo "❌ Java-Sleuth is not running"
        [[ -f "\$PID_FILE" ]] && rm -f "\$PID_FILE"
        exit 1
    fi
}

# Main script logic
case "\${1:-}" in
    start)
        start_sleuth
        ;;
    stop)
        stop_sleuth
        ;;
    restart)
        stop_sleuth
        sleep 2
        start_sleuth
        ;;
    status)
        status_sleuth
        ;;
    *)
        echo "Usage: \$0 {start|stop|restart|status}"
        echo
        echo "Commands:"
        echo "  start   - Start Java-Sleuth"
        echo "  stop    - Stop Java-Sleuth"
        echo "  restart - Restart Java-Sleuth"
        echo "  status  - Check Java-Sleuth status"
        exit 1
        ;;
esac
EOF

    chmod +x "${DEPLOY_DIR}/bin/sleuth-production.sh"

    log "Production startup script created"
}

# Validate deployment
validate_deployment() {
    log "Validating deployment..."

    # Check files exist
    local files=(
        "${DEPLOY_DIR}/lib/${JAR_NAME}"
        "${DEPLOY_DIR}/bin/sleuth.sh"
        "${DEPLOY_DIR}/bin/sleuth-production.sh"
        "${DEPLOY_DIR}/bin/monitor.sh"
        "${CONFIG_DIR}/sleuth.properties"
        "${CONFIG_DIR}/jvm.conf"
        "${CONFIG_DIR}/logging.properties"
    )

    for file in "${files[@]}"; do
        if [[ ! -f "$file" ]]; then
            error "Required file missing: $file"
        fi
    done

    # Check permissions
    if [[ ! -x "${DEPLOY_DIR}/bin/sleuth-production.sh" ]]; then
        error "Startup script is not executable"
    fi

    # Check Java can load the JAR
    if ! java -jar "${DEPLOY_DIR}/lib/${JAR_NAME}" --version 2>/dev/null; then
        warn "Could not validate JAR file with --version flag"
    fi

    log "Deployment validation complete"
}

# Print deployment summary
print_summary() {
    log "Deployment completed successfully! 🎉"
    echo
    echo "=== DEPLOYMENT SUMMARY ==="
    echo "Installation Directory: $DEPLOY_DIR"
    echo "Configuration Directory: $CONFIG_DIR"
    echo "Logs Directory: $LOGS_DIR"
    echo "JAR File: ${DEPLOY_DIR}/lib/${JAR_NAME}"
    echo
    echo "=== NEXT STEPS ==="
    echo "1. Review configuration files in $CONFIG_DIR"
    echo "2. Start the service:"
    echo "   ${DEPLOY_DIR}/bin/sleuth-production.sh start"
    echo "   OR (if using systemd):"
    echo "   sudo systemctl enable $SERVICE_NAME"
    echo "   sudo systemctl start $SERVICE_NAME"
    echo
    echo "3. Monitor the service:"
    echo "   ${DEPLOY_DIR}/bin/monitor.sh"
    echo "   tail -f ${LOGS_DIR}/sleuth.out"
    echo
    echo "4. Connect to the service:"
    echo "   telnet localhost 3658"
    echo
    echo "=== SECURITY NOTES ==="
    echo "- Review firewall settings for port 3658"
    echo "- Consider setting up authentication"
    echo "- Monitor audit logs in ${LOGS_DIR}"
    echo "- JMX is exposed on port 9999 for monitoring"
    echo
}

# Main deployment function
main() {
    echo "🚀 Java-Sleuth Production Deployment"
    echo "======================================"
    echo

    check_root
    check_prerequisites
    build_project
    create_directories
    deploy_files
    create_startup_script
    setup_monitoring
    validate_deployment
    print_summary
}

# Run main function
main "$@"