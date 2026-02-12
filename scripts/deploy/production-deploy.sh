#!/bin/bash

# Java-Sleuth Production Deployment Script
# This script handles production deployment with comprehensive safety checks

set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_NAME_AGENT_BOOTSTRAP="java-sleuth-agent"
PROJECT_NAME_AGENT_CORE="java-sleuth-agent-core"
PROJECT_NAME_LAUNCHER="java-sleuth-launcher"
VERSION="1.0.0"
AGENT_BOOTSTRAP_JAR_NAME="${PROJECT_NAME_AGENT_BOOTSTRAP}-${VERSION}-jar-with-dependencies.jar"
AGENT_CORE_JAR_NAME="${PROJECT_NAME_AGENT_CORE}-${VERSION}-jar-with-dependencies.jar"
LAUNCHER_JAR_NAME="${PROJECT_NAME_LAUNCHER}-${VERSION}-jar-with-dependencies.jar"
AGENT_BOOTSTRAP_JAR="agent/target/${AGENT_BOOTSTRAP_JAR_NAME}"
AGENT_CORE_JAR="core/target/${AGENT_CORE_JAR_NAME}"
LAUNCHER_JAR="launcher/target/${LAUNCHER_JAR_NAME}"
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

    if [[ ! -f "$AGENT_BOOTSTRAP_JAR" ]]; then
        error "Build failed - bootstrap agent JAR not found: $AGENT_BOOTSTRAP_JAR"
    fi
    if [[ ! -f "$AGENT_CORE_JAR" ]]; then
        error "Build failed - agent core JAR not found: $AGENT_CORE_JAR"
    fi
    if [[ ! -f "$LAUNCHER_JAR" ]]; then
        error "Build failed - JAR file not found: $LAUNCHER_JAR"
    fi

    log "Project built successfully"
}

# Deploy files
deploy_files() {
    log "Deploying files..."

    # Backup existing installation
    if [[ -f "${DEPLOY_DIR}/lib/${AGENT_BOOTSTRAP_JAR_NAME}" ]] || [[ -f "${DEPLOY_DIR}/lib/${AGENT_CORE_JAR_NAME}" ]] || [[ -f "${DEPLOY_DIR}/lib/${LAUNCHER_JAR_NAME}" ]]; then
        local backup_name="backup-$(date +%Y%m%d_%H%M%S)"
        mkdir -p "${BACKUP_DIR}/${backup_name}"
        if [[ -f "${DEPLOY_DIR}/lib/${AGENT_BOOTSTRAP_JAR_NAME}" ]]; then
            cp "${DEPLOY_DIR}/lib/${AGENT_BOOTSTRAP_JAR_NAME}" "${BACKUP_DIR}/${backup_name}/"
        fi
        if [[ -f "${DEPLOY_DIR}/lib/${AGENT_CORE_JAR_NAME}" ]]; then
            cp "${DEPLOY_DIR}/lib/${AGENT_CORE_JAR_NAME}" "${BACKUP_DIR}/${backup_name}/"
        fi
        if [[ -f "${DEPLOY_DIR}/lib/${LAUNCHER_JAR_NAME}" ]]; then
            cp "${DEPLOY_DIR}/lib/${LAUNCHER_JAR_NAME}" "${BACKUP_DIR}/${backup_name}/"
        fi
        log "Previous installation backed up to ${BACKUP_DIR}/${backup_name}"
    fi

    # Copy JAR files
    cp "$AGENT_BOOTSTRAP_JAR" "${DEPLOY_DIR}/lib/"
    cp "$AGENT_CORE_JAR" "${DEPLOY_DIR}/lib/"
    cp "$LAUNCHER_JAR" "${DEPLOY_DIR}/lib/"

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
logging.performance.enabled=false
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

    cat > "${DEPLOY_DIR}/bin/sleuth-production.sh" << 'EOF'
#!/bin/bash

# Java-Sleuth Production Wrapper (interactive attach)
#
# Java-Sleuth is designed as an interactive attach tool:
# - java-sleuth-launcher: runs on operator machine/server
# - java-sleuth-agent (bootstrap): injected into target JVM (Attach API / -javaagent)
# - java-sleuth-agent-core: isolated implementation loaded by the bootstrap agent

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SLEUTH_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

export SLEUTH_CONFIG_FILE="${SLEUTH_HOME}/config/sleuth.properties"

exec "${SLEUTH_HOME}/bin/sleuth.sh" "$@"
EOF

    chmod +x "${DEPLOY_DIR}/bin/sleuth-production.sh"

    log "Production startup script created"
}

# Validate deployment
validate_deployment() {
    log "Validating deployment..."

    # Check files exist
    local files=(
        "${DEPLOY_DIR}/lib/${AGENT_BOOTSTRAP_JAR_NAME}"
        "${DEPLOY_DIR}/lib/${AGENT_CORE_JAR_NAME}"
        "${DEPLOY_DIR}/lib/${LAUNCHER_JAR_NAME}"
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
    echo "Launcher JAR: ${DEPLOY_DIR}/lib/${LAUNCHER_JAR_NAME}"
    echo "Agent Bootstrap JAR: ${DEPLOY_DIR}/lib/${AGENT_BOOTSTRAP_JAR_NAME}"
    echo "Agent Core JAR: ${DEPLOY_DIR}/lib/${AGENT_CORE_JAR_NAME}"
    echo
    echo "=== NEXT STEPS ==="
    echo "1. Review configuration files in $CONFIG_DIR"
    echo "2. Start Java-Sleuth (interactive attach):"
    echo "   ${DEPLOY_DIR}/bin/sleuth-production.sh"
    echo
    echo "3. Monitoring:"
    echo "   ${DEPLOY_DIR}/bin/monitor.sh"
    echo
    echo "4. Notes:"
    echo "   - The agent runs inside the target JVM and listens on the configured port (default 3658)."
    echo "   - Avoid exposing the port to untrusted networks; consider enabling security.mode=hmac."
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
