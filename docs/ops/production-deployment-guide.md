# Java-Sleuth Production Deployment Guide

## Table of Contents

1. [Overview](#overview)
2. [System Requirements](#system-requirements)
3. [Pre-Deployment Checklist](#pre-deployment-checklist)
4. [Installation Methods](#installation-methods)
5. [Configuration](#configuration)
6. [Security Setup](#security-setup)
7. [Monitoring and Observability](#monitoring-and-observability)
8. [Performance Tuning](#performance-tuning)
9. [Troubleshooting](#troubleshooting)
10. [Maintenance Procedures](#maintenance-procedures)
11. [Disaster Recovery](#disaster-recovery)

## Overview

Java-Sleuth is a production-ready Java diagnostic and monitoring agent that provides real-time insights into JVM applications. This guide covers the complete deployment process for production environments.

### Key Features
- ✅ Real-time JVM monitoring and diagnostics
- ✅ Advanced performance optimization with multi-tier caching
- ✅ Comprehensive security with authentication and authorization
- ✅ Production-grade monitoring with JMX and metrics collection
- ✅ Graceful shutdown and restart mechanisms
- ✅ Audit logging and security event tracking

## System Requirements

### Hardware Requirements

| Component | Minimum | Recommended | High-Load |
|-----------|---------|-------------|-----------|
| CPU | 2 cores | 4 cores | 8+ cores |
| RAM | 4 GB | 8 GB | 16+ GB |
| Disk | 10 GB | 50 GB | 100+ GB |
| Network | 100 Mbps | 1 Gbps | 10 Gbps |

### Software Requirements

- **Java**: OpenJDK 8+ or Oracle JDK 8+
- **Operating System**: Linux (Ubuntu 18.04+, RHEL 7+, CentOS 7+)
- **Network**: Port 3658 (default) and 9999 (JMX) available
- **Optional**: systemd for service management

### JVM Requirements

```bash
# Minimum heap for agent
-Xms512m -Xmx2g

# Recommended for production
-Xms2g -Xmx4g

# High-load environments
-Xms4g -Xmx8g
```

## Pre-Deployment Checklist

### Infrastructure Preparation

- [ ] Verify hardware meets minimum requirements
- [ ] Ensure Java is installed and configured
- [ ] Check network connectivity and firewall rules
- [ ] Validate disk space and I/O performance
- [ ] Configure system limits (ulimits)
- [ ] Set up log rotation
- [ ] Configure NTP for time synchronization

### Security Preparation

- [ ] Create dedicated user account for Java-Sleuth
- [ ] Configure SSL certificates (if external access required)
- [ ] Set up firewall rules
- [ ] Review security policies and compliance requirements
- [ ] Configure audit logging destinations
- [ ] Set up intrusion detection (if required)

### Monitoring Preparation

- [ ] Set up monitoring infrastructure (Prometheus, Grafana, etc.)
- [ ] Configure log aggregation (ELK stack, Splunk, etc.)
- [ ] Set up alerting rules and notification channels
- [ ] Prepare runbooks for common scenarios
- [ ] Configure backup and retention policies

## Installation Methods

### Method 1: Automated Deployment Script (Recommended)

```bash
# Download and run the production deployment script
./scripts/deploy/production-deploy.sh

# Follow the interactive prompts
# The script will:
# - Build the project
# - Create directory structure
# - Deploy files and configurations
# - Set up systemd service
# - Configure monitoring
```

### Method 2: Manual Installation

#### Step 1: Build the Project

```bash
# Clone repository
git clone https://github.com/your-org/java-sleuth.git
cd java-sleuth

# Build with Maven
mvn clean package -DskipTests

# Verify build
ls -la target/java-sleuth-*-jar-with-dependencies.jar
```

#### Step 2: Create Directory Structure

```bash
# Create installation directories
sudo mkdir -p /opt/java-sleuth/{bin,lib,config,logs,backup}

# Set permissions
sudo chown -R sleuth:sleuth /opt/java-sleuth
sudo chmod 755 /opt/java-sleuth
```

#### Step 3: Deploy Files

```bash
# Copy JAR file
cp target/java-sleuth-*-jar-with-dependencies.jar /opt/java-sleuth/lib/

# Copy configuration files
cp config-templates/production-sleuth.properties /opt/java-sleuth/config/sleuth.properties
cp config-templates/jvm-production.conf /opt/java-sleuth/config/jvm.conf

# Copy startup scripts
cp sleuth.sh /opt/java-sleuth/bin/
chmod +x /opt/java-sleuth/bin/sleuth.sh
```

### Method 3: Container Deployment

#### Docker

```dockerfile
FROM openjdk:8-jre-alpine

COPY target/java-sleuth-*-jar-with-dependencies.jar /app/java-sleuth.jar
COPY config-templates/production-sleuth.properties /app/config/sleuth.properties

EXPOSE 3658 9999

ENTRYPOINT ["java", "-javaagent:/app/java-sleuth.jar", "-jar", "/app/java-sleuth.jar"]
```

#### Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: java-sleuth
spec:
  replicas: 1
  selector:
    matchLabels:
      app: java-sleuth
  template:
    metadata:
      labels:
        app: java-sleuth
    spec:
      containers:
      - name: java-sleuth
        image: java-sleuth:1.0.0
        ports:
        - containerPort: 3658
        - containerPort: 9999
        resources:
          requests:
            memory: "2Gi"
            cpu: "1"
          limits:
            memory: "4Gi"
            cpu: "2"
```

## Configuration

### Core Configuration Files

#### 1. Application Configuration (`sleuth.properties`)

```properties
# Production configuration template
server.port=3658
server.max.connections=50
server.connection.timeout=60000

# Performance settings
performance.cache.ttl=10000
performance.thread.pool.core=8
performance.thread.pool.max=32

# Security settings
security.input.validation=true
security.audit.logging=true
security.allowed.commands=*

# Monitoring settings
monitoring.metrics.enabled=true
monitoring.health.checks=true
monitoring.jmx.enabled=true
```

#### 2. JVM Configuration (`jvm.conf`)

```bash
# Memory settings
-Xms2g
-Xmx4g
-XX:MetaspaceSize=256m
-XX:MaxMetaspaceSize=512m

# GC settings
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=16m

# Monitoring
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=9999
```

#### 3. Logging Configuration (`logging.properties`)

```properties
# Root logger configuration
.level=INFO
handlers=java.util.logging.FileHandler, java.util.logging.ConsoleHandler

# File handler
java.util.logging.FileHandler.pattern=/opt/java-sleuth/logs/sleuth-%g.log
java.util.logging.FileHandler.limit=10485760
java.util.logging.FileHandler.count=10
```

### Environment-Specific Configuration

#### Development Environment

```properties
# Development overrides
logging.level=DEBUG
security.input.validation=false
monitoring.metrics.enabled=true
```

#### Staging Environment

```properties
# Staging configuration
server.max.connections=25
performance.cache.ttl=5000
security.input.validation=true
```

#### Production Environment

```properties
# Production configuration
server.max.connections=50
performance.cache.ttl=10000
security.input.validation=true
security.audit.logging=true
monitoring.metrics.enabled=true
```

## Security Setup

### Network Security

#### Firewall Configuration

```bash
# Allow Java-Sleuth port
sudo ufw allow 3658/tcp

# Allow JMX port (internal networks only)
sudo ufw allow from 10.0.0.0/8 to any port 9999

# Enable firewall
sudo ufw enable
```

#### SSL/TLS Configuration

For external access, configure SSL termination:

```bash
# Generate self-signed certificate (development)
openssl req -x509 -newkey rsa:4096 -keyout sleuth-key.pem -out sleuth-cert.pem -days 365

# Use proper CA-signed certificates in production
# Configure reverse proxy (nginx/Apache) for SSL termination
```

### Authentication and Authorization

#### User Management

```bash
# Create dedicated user
sudo useradd -r -s /bin/false sleuth
sudo usermod -aG sleuth sleuth

# Set file permissions
sudo chown -R sleuth:sleuth /opt/java-sleuth
sudo chmod 750 /opt/java-sleuth/config
sudo chmod 640 /opt/java-sleuth/config/*.properties
```

#### Access Control

Configure role-based access in the application:

```properties
# Security configuration（以当前代码实现为准）
# - 当前版本未实现 security.authentication.enabled / security.session.timeout 这类配置项
# - 默认关闭匿名 viewer：连接后需先执行 auth
# - 非回环绑定时禁止 security.mode=off（会拒绝启动），建议启用 hmac 并设置强随机 secret
security.authorization.enabled=true
security.anonymous.viewer=false
security.mode=hmac
security.hmac.secret=<a-strong-random-secret>
security.hmac.timestamp.window.ms=30000
security.hmac.nonce.cache.size=10000
```

### Audit Logging

#### Audit Configuration

```properties
# Enable comprehensive audit logging
security.audit.logging=true
logging.audit.enabled=true
monitoring.security.events=true
```

#### Log Monitoring

```bash
# Monitor security events
tail -f /opt/java-sleuth/logs/sleuth-security.log

# Set up log rotation
sudo logrotate -d /etc/logrotate.d/java-sleuth
```

## Monitoring and Observability

### Health Checks

#### Built-in Health Endpoints

```bash
# Health check
echo "health" | nc localhost 3658

# Status check
echo "status" | nc localhost 3658

# Metrics
echo "metrics" | nc localhost 3658
```

#### External Monitoring Integration

##### Prometheus Metrics

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'java-sleuth'
    static_configs:
      - targets: ['localhost:9999']
    metrics_path: /metrics
    scrape_interval: 30s
```

##### Grafana Dashboard

```json
{
  "dashboard": {
    "title": "Java-Sleuth Monitoring",
    "panels": [
      {
        "title": "Memory Usage",
        "type": "graph",
        "targets": [
          {
            "expr": "jvm_memory_used_bytes / jvm_memory_max_bytes * 100"
          }
        ]
      }
    ]
  }
}
```

### Log Management

#### Centralized Logging

```yaml
# ELK Stack configuration
filebeat.inputs:
- type: log
  paths:
    - /opt/java-sleuth/logs/*.log
  fields:
    service: java-sleuth
    environment: production
```

#### Log Analysis

```bash
# Common log analysis commands
grep "ERROR" /opt/java-sleuth/logs/sleuth-audit.log
grep "SECURITY" /opt/java-sleuth/logs/sleuth-security.log
awk '/WARNING/ {print $1, $2, $NF}' /opt/java-sleuth/logs/sleuth.log
```

### Alerting Rules

#### Memory Alerts

```yaml
# Prometheus alerting rules
groups:
- name: java-sleuth
  rules:
  - alert: HighMemoryUsage
    expr: heap_usage_percent > 85
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Java-Sleuth high memory usage"
```

#### Performance Alerts

```yaml
- alert: HighResponseTime
  expr: command_avg_response_time > 1000
  for: 2m
  labels:
    severity: critical
  annotations:
    summary: "Java-Sleuth high response time"
```

## Performance Tuning

### JVM Tuning

#### Memory Optimization

```bash
# For high-throughput applications
-Xms4g -Xmx8g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200

# For low-latency applications
-Xms8g -Xmx8g
-XX:+UseZGC  # Java 11+
-XX:+UseLargePages
```

#### GC Tuning

```bash
# G1GC tuning
-XX:G1HeapRegionSize=16m
-XX:G1NewSizePercent=30
-XX:G1MaxNewSizePercent=40
-XX:+G1UseAdaptiveIHOP

# Parallel GC tuning
-XX:+UseParallelGC
-XX:ParallelGCThreads=8
-XX:MaxGCPauseMillis=200
```

### Application Tuning

#### Cache Optimization

```properties
# Adjust cache settings based on workload
performance.cache.ttl=5000    # Fast-changing data
performance.cache.ttl=30000   # Relatively static data
performance.cache.ttl=300000  # Static data
```

#### Thread Pool Tuning

```properties
# Adjust based on CPU cores and workload
performance.thread.pool.core=8   # Number of CPU cores
performance.thread.pool.max=32   # 4x CPU cores
```

### Network Tuning

#### TCP Settings

```bash
# Optimize TCP settings for high-throughput
echo 'net.core.rmem_max = 16777216' >> /etc/sysctl.conf
echo 'net.core.wmem_max = 16777216' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_rmem = 4096 65536 16777216' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_wmem = 4096 65536 16777216' >> /etc/sysctl.conf
sysctl -p
```

#### Connection Limits

```bash
# Increase connection limits
echo 'sleuth soft nofile 65536' >> /etc/security/limits.conf
echo 'sleuth hard nofile 65536' >> /etc/security/limits.conf
```

## Troubleshooting

### Common Issues and Solutions

#### High Memory Usage

**Symptoms:**
- OutOfMemoryError in logs
- High heap usage warnings
- Slow response times

**Investigation:**
```bash
# Check memory usage
echo "memory" | nc localhost 3658

# Generate heap dump
jcmd <PID> GC.run_finalization
jcmd <PID> VM.gc
jmap -dump:live,format=b,file=heapdump.hprof <PID>

# Analyze with Eclipse MAT or VisualVM
```

**Solutions:**
- Increase heap size: `-Xmx4g` → `-Xmx8g`
- Tune cache TTL settings
- Review for memory leaks
- Optimize GC settings

#### High Response Times

**Symptoms:**
- Slow command execution
- Client timeouts
- High CPU usage

**Investigation:**
```bash
# Check performance metrics
echo "metrics" | nc localhost 3658

# Profile application
jstack <PID> > thread-dump.txt

# Monitor GC
jstat -gc -t <PID> 5s
```

**Solutions:**
- Tune GC settings
- Increase thread pool size
- Optimize cache hit ratios
- Review slow operations

#### Connection Issues

**Symptoms:**
- Cannot connect to port 3658
- Connection refused errors
- Intermittent connectivity

**Investigation:**
```bash
# Check if service is running
systemctl status java-sleuth

# Check port binding
netstat -tlnp | grep 3658

# Check firewall
sudo ufw status
```

**Solutions:**
- Restart service: `systemctl restart java-sleuth`
- Check firewall rules
- Verify configuration
- Check for port conflicts

### Diagnostic Commands

#### Health Diagnostics

```bash
# Quick health check
./monitor.sh

# Detailed status
echo "status" | nc localhost 3658

# Check JVM metrics
jstat -gc <PID>
jinfo <PID>
```

#### Performance Diagnostics

```bash
# Profile for 30 seconds
jcmd <PID> JFR.start duration=30s filename=profile.jfr

# Thread analysis
jstack <PID>

# Memory analysis
jmap -histo <PID>
```

#### Log Analysis

```bash
# Error analysis
grep -i error /opt/java-sleuth/logs/*.log

# Security events
grep "SECURITY" /opt/java-sleuth/logs/sleuth-security.log

# Performance issues
grep "SLOW" /opt/java-sleuth/logs/sleuth.log
```

## Maintenance Procedures

### Regular Maintenance Tasks

#### Daily Tasks

- [ ] Check service health and status
- [ ] Review error logs for issues
- [ ] Monitor memory and CPU usage
- [ ] Verify backup completion

```bash
# Daily maintenance script
#!/bin/bash
echo "=== Daily Java-Sleuth Maintenance ==="
systemctl status java-sleuth
./monitor.sh
tail -50 /opt/java-sleuth/logs/sleuth.out | grep -i error
```

#### Weekly Tasks

- [ ] Review performance metrics and trends
- [ ] Analyze GC logs for optimization opportunities
- [ ] Check disk space and log rotation
- [ ] Update security patches if available

```bash
# Weekly maintenance script
#!/bin/bash
echo "=== Weekly Java-Sleuth Maintenance ==="
df -h /opt/java-sleuth
find /opt/java-sleuth/logs -name "*.log" -mtime +7 -ls
```

#### Monthly Tasks

- [ ] Review and update configurations
- [ ] Performance benchmarking
- [ ] Security audit and review
- [ ] Capacity planning assessment

### Updates and Upgrades

#### Patch Updates

```bash
# 1. Download new version
wget https://releases.java-sleuth.com/v1.0.1/java-sleuth-1.0.1.jar

# 2. Backup current version
cp /opt/java-sleuth/lib/java-sleuth-1.0.0.jar /opt/java-sleuth/backup/

# 3. Stop service
systemctl stop java-sleuth

# 4. Replace JAR
cp java-sleuth-1.0.1.jar /opt/java-sleuth/lib/

# 5. Update configuration if needed
# Review release notes for configuration changes

# 6. Start service
systemctl start java-sleuth

# 7. Verify deployment
./monitor.sh
```

#### Major Upgrades

```bash
# 1. Full backup
tar czf java-sleuth-backup-$(date +%Y%m%d).tar.gz /opt/java-sleuth/

# 2. Test upgrade in staging environment first

# 3. Schedule maintenance window

# 4. Follow patch update process with additional testing

# 5. Monitor for 24 hours post-upgrade
```

### Backup and Recovery

#### Backup Strategy

```bash
# Configuration backup
tar czf config-backup-$(date +%Y%m%d).tar.gz /opt/java-sleuth/config/

# Full backup
rsync -av /opt/java-sleuth/ /backup/java-sleuth/

# Database backup (if applicable)
# Application state is generally stateless
```

#### Recovery Procedures

```bash
# Configuration recovery
tar xzf config-backup-20231201.tar.gz -C /

# Full recovery
systemctl stop java-sleuth
rsync -av /backup/java-sleuth/ /opt/java-sleuth/
systemctl start java-sleuth
```

## Disaster Recovery

### Failover Procedures

#### Single Instance Failure

```bash
# 1. Detect failure
systemctl status java-sleuth

# 2. Attempt restart
systemctl restart java-sleuth

# 3. If restart fails, investigate
journalctl -u java-sleuth -f

# 4. Restore from backup if necessary
./restore-from-backup.sh
```

#### Complete System Failure

```bash
# 1. Provision new system
# 2. Install Java and dependencies
# 3. Restore configuration and data
tar xzf java-sleuth-backup-latest.tar.gz -C /opt/
# 4. Start service
systemctl enable java-sleuth
systemctl start java-sleuth
# 5. Verify functionality
./monitor.sh
```

### High Availability Setup

#### Load Balancer Configuration

```nginx
# nginx configuration
upstream java-sleuth {
    server sleuth1.example.com:3658;
    server sleuth2.example.com:3658;
    server sleuth3.example.com:3658;
}

server {
    listen 80;
    location / {
        proxy_pass http://java-sleuth;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

#### Cluster Configuration

```properties
# Node 1 configuration
server.port=3658
cluster.node.id=node1
cluster.peers=node2:3658,node3:3658

# Node 2 configuration
server.port=3658
cluster.node.id=node2
cluster.peers=node1:3658,node3:3658
```

### Business Continuity

#### Communication Plan

1. **Incident Detection**: Automated monitoring alerts
2. **Escalation Matrix**:
   - Level 1: Operations team
   - Level 2: Engineering team
   - Level 3: Management
3. **Communication Channels**:
   - Slack: #java-sleuth-ops
   - Email: ops@company.com
   - Phone: Emergency on-call rotation

#### Service Level Objectives (SLOs)

- **Availability**: 99.9% uptime
- **Performance**: 95% of requests < 100ms
- **Recovery Time**: < 15 minutes for service restart
- **Recovery Point**: < 1 hour data loss maximum

---

## Appendices

### A. Command Reference

#### Management Commands

```bash
# Service management
systemctl start java-sleuth
systemctl stop java-sleuth
systemctl restart java-sleuth
systemctl status java-sleuth

# Manual startup
/opt/java-sleuth/bin/sleuth-production.sh start
/opt/java-sleuth/bin/sleuth-production.sh stop
/opt/java-sleuth/bin/sleuth-production.sh status
```

#### Diagnostic Commands

```bash
# Health and status
echo "health" | nc localhost 3658
echo "status" | nc localhost 3658
echo "metrics" | nc localhost 3658

# JVM diagnostics
jstat -gc <PID>
jstack <PID>
jmap -histo <PID>
```

### B. Configuration Reference

#### Complete Configuration Template

```properties
# Server Configuration
server.port=3658
server.max.connections=50
server.connection.timeout=60000
server.socket.timeout=2000

# Performance Configuration
performance.cache.ttl=10000
performance.thread.pool.core=8
performance.thread.pool.max=32
performance.command.timeout=120000

# Security Configuration
security.input.validation=true
security.audit.logging=true
security.max.command.length=2000
security.allowed.commands=*

# Monitoring Configuration
monitoring.metrics.enabled=true
monitoring.health.checks=true
monitoring.cache.cleanup.interval=300000
monitoring.jmx.enabled=true

# Logging Configuration
logging.level=INFO
logging.audit.enabled=true
logging.performance.enabled=true

# Production Configuration
production.mode=true
production.detailed.errors=false
production.dev.features=false
```

### C. Security Checklist

- [ ] Dedicated user account created
- [ ] File permissions properly set
- [ ] Firewall rules configured
- [ ] SSL/TLS configured for external access
- [ ] Audit logging enabled
- [ ] Input validation enabled
- [ ] JMX access restricted
- [ ] Regular security reviews scheduled

### D. Performance Baseline

#### Expected Performance Metrics

| Metric | Target | Acceptable | Critical |
|--------|--------|------------|----------|
| Memory Usage | < 70% | < 85% | > 90% |
| Response Time | < 50ms | < 100ms | > 500ms |
| Throughput | > 1000 req/s | > 500 req/s | < 100 req/s |
| Error Rate | < 0.1% | < 1% | > 5% |
| GC Pause | < 50ms | < 200ms | > 1s |

---

*This document should be reviewed and updated quarterly or after major system changes.*
