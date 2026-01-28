# Java-Sleuth Operations Runbook

## Quick Reference

### Emergency Contacts
- **On-Call Engineer**: +1-XXX-XXX-XXXX
- **Team Lead**: +1-XXX-XXX-XXXX
- **Escalation**: manager@company.com

### Critical Commands
```bash
# Service status
systemctl status java-sleuth

# Emergency restart
systemctl restart java-sleuth

# Health check
echo "health" | nc localhost 3658

# View recent logs
tail -f /opt/java-sleuth/logs/sleuth.out
```

### Service Endpoints
- **Main Service**: `localhost:3658`
- **JMX Monitoring**: `localhost:9999`
- **Health Check**: `echo "health" | nc localhost 3658`

---

## Incident Response Procedures

### Severity Levels

#### SEV-1 (Critical)
**Definition**: Service completely unavailable
**Response Time**: 15 minutes
**Escalation**: Immediate

**Symptoms**:
- Service cannot be reached on port 3658
- Health checks fail consistently
- Complete application crash

**Immediate Actions**:
1. **Verify the issue** (2 minutes)
   ```bash
   systemctl status java-sleuth
   nc -zv localhost 3658
   ```

2. **Attempt service restart** (3 minutes)
   ```bash
   systemctl restart java-sleuth
   sleep 30
   echo "health" | nc localhost 3658
   ```

3. **If restart fails, check logs** (5 minutes)
   ```bash
   journalctl -u java-sleuth --since "10 minutes ago"
   tail -100 /opt/java-sleuth/logs/sleuth.out
   ```

4. **Escalate immediately** if not resolved in 15 minutes

#### SEV-2 (High)
**Definition**: Significant performance degradation
**Response Time**: 30 minutes
**Escalation**: 1 hour

**Symptoms**:
- Response times > 5 seconds
- Memory usage > 90%
- High error rates (> 5%)

**Actions**:
1. **Check system resources**
   ```bash
   top -p $(pgrep -f java-sleuth)
   df -h /opt/java-sleuth
   ```

2. **Check JVM metrics**
   ```bash
   jstat -gc $(pgrep -f java-sleuth)
   echo "metrics" | nc localhost 3658
   ```

3. **Review recent changes**
   - Check deployment logs
   - Review configuration changes
   - Check for system updates

#### SEV-3 (Medium)
**Definition**: Minor issues not affecting core functionality
**Response Time**: 2 hours
**Escalation**: 4 hours

**Symptoms**:
- Occasional slow responses
- Non-critical errors in logs
- Minor memory increases

#### SEV-4 (Low)
**Definition**: Monitoring alerts, questions
**Response Time**: Next business day
**Escalation**: Not required

---

## Common Scenarios and Responses

### Scenario 1: Service Won't Start

**Symptoms**:
- `systemctl start java-sleuth` fails
- Exit code non-zero
- Error in systemd logs

**Diagnostic Steps**:
```bash
# Check service status
systemctl status java-sleuth

# Check systemd logs
journalctl -u java-sleuth --since "1 hour ago"

# Check application logs
tail -50 /opt/java-sleuth/logs/sleuth.out

# Verify configuration
java -jar /opt/java-sleuth/lib/java-sleuth-*.jar --validate-config

# Check file permissions
ls -la /opt/java-sleuth/
ls -la /opt/java-sleuth/config/
```

**Common Causes and Solutions**:

1. **Configuration Error**
   ```bash
   # Check configuration syntax
   grep -n "=" /opt/java-sleuth/config/sleuth.properties | grep -v "^#"

   # Restore from backup if needed
   cp /opt/java-sleuth/backup/sleuth.properties.bak /opt/java-sleuth/config/sleuth.properties
   ```

2. **Port Already in Use**
   ```bash
   # Check what's using the port
   lsof -i :3658
   netstat -tlnp | grep 3658

   # Kill conflicting process or change port
   ```

3. **File Permissions**
   ```bash
   # Fix permissions
   sudo chown -R sleuth:sleuth /opt/java-sleuth
   sudo chmod 755 /opt/java-sleuth/bin/*.sh
   ```

4. **Insufficient Memory**
   ```bash
   # Check available memory
   free -h

   # Reduce heap size temporarily
   export JAVA_OPTS="-Xms512m -Xmx1g"
   ```

### Scenario 2: High Memory Usage

**Symptoms**:
- Memory usage > 85%
- OutOfMemoryError in logs
- Slow garbage collection

**Investigation**:
```bash
# Check current memory usage
echo "memory" | nc localhost 3658

# Get heap dump
jcmd $(pgrep -f java-sleuth) GC.run_finalization
jmap -dump:live,format=b,file=/tmp/heapdump-$(date +%Y%m%d-%H%M).hprof $(pgrep -f java-sleuth)

# Analyze GC
jstat -gc -t $(pgrep -f java-sleuth) 5s 12

# Check for memory leaks
jcmd $(pgrep -f java-sleuth) VM.classloader_stats
```

**Immediate Actions**:
1. **Force garbage collection**
   ```bash
   jcmd $(pgrep -f java-sleuth) GC.run
   ```

2. **Clear caches**
   ```bash
   echo "cache clear" | nc localhost 3658
   ```

3. **Restart service if critical**
   ```bash
   systemctl restart java-sleuth
   ```

**Long-term Solutions**:
- Increase heap size: `-Xmx4g` → `-Xmx8g`
- Tune cache TTL settings
- Optimize GC parameters
- Review for memory leaks

### Scenario 3: High Response Times

**Symptoms**:
- Commands taking > 1 second
- Client timeouts
- Queue buildup

**Investigation**:
```bash
# Check thread status
jstack $(pgrep -f java-sleuth) > /tmp/threadump-$(date +%Y%m%d-%H%M).txt

# Check performance metrics
echo "metrics" | nc localhost 3658

# Monitor real-time performance
watch -n 5 'echo "status" | nc localhost 3658'

# Check system load
top -p $(pgrep -f java-sleuth)
iostat -x 1 5
```

**Actions**:
1. **Check for thread deadlocks**
   ```bash
   jstack $(pgrep -f java-sleuth) | grep -A 5 -B 5 "BLOCKED"
   ```

2. **Increase thread pool size temporarily**
   ```bash
   # Update configuration
   echo "performance.thread.pool.max=64" >> /opt/java-sleuth/config/sleuth.properties
   systemctl restart java-sleuth
   ```

3. **Clear performance bottlenecks**
   ```bash
   # Clear caches if hit ratio is low
   echo "cache clear" | nc localhost 3658
   ```

### Scenario 4: Connection Issues

**Symptoms**:
- Cannot connect to port 3658
- Connection refused
- Intermittent connectivity

**Investigation**:
```bash
# Verify service is running
systemctl status java-sleuth
ps aux | grep java-sleuth

# Check port binding
netstat -tlnp | grep 3658
lsof -i :3658

# Test connectivity
nc -zv localhost 3658
telnet localhost 3658

# Check firewall
sudo ufw status
iptables -L | grep 3658
```

**Actions**:
1. **Restart networking if needed**
   ```bash
   systemctl restart java-sleuth
   ```

2. **Check firewall rules**
   ```bash
   sudo ufw allow 3658/tcp
   sudo ufw reload
   ```

3. **Verify configuration**
   ```bash
   grep "server.port" /opt/java-sleuth/config/sleuth.properties
   ```

### Scenario 5: Security Alerts

**Symptoms**:
- Security violation logs
- Unusual authentication attempts
- Suspicious command patterns

**Investigation**:
```bash
# Check security logs
tail -100 /opt/java-sleuth/logs/sleuth-security.log

# Look for security violations
grep "SECURITY_VIOLATION" /opt/java-sleuth/logs/sleuth-audit.log

# Check failed authentication attempts
grep "AUTHENTICATION_FAILED" /opt/java-sleuth/logs/sleuth-audit.log

# Review recent connections
grep "CONNECTION" /opt/java-sleuth/logs/sleuth-audit.log | tail -20
```

**Immediate Actions**:
1. **Block suspicious IPs**
   ```bash
   sudo ufw deny from SUSPICIOUS_IP
   ```

2. **Enable emergency lockdown if needed**
   ```bash
   echo "security lockdown" | nc localhost 3658
   ```

3. **Review audit trail**
   ```bash
   awk '/SECURITY/ {print $1, $2, $NF}' /opt/java-sleuth/logs/sleuth-audit.log
   ```

---

## Monitoring and Alerting

### Key Metrics to Monitor

#### Performance Metrics
- **Response Time**: Target < 100ms, Alert > 500ms
- **Throughput**: Target > 1000 req/s, Alert < 100 req/s
- **Error Rate**: Target < 0.1%, Alert > 5%
- **Cache Hit Ratio**: Target > 80%, Alert < 50%

#### Resource Metrics
- **Memory Usage**: Target < 70%, Alert > 85%
- **CPU Usage**: Target < 60%, Alert > 80%
- **Disk Usage**: Target < 70%, Alert > 85%
- **Network Connections**: Target < 80% of max, Alert > 90%

#### Availability Metrics
- **Service Uptime**: Target 99.9%, Alert < 99%
- **Health Check Success**: Target 100%, Alert < 95%

### Alert Definitions

#### Critical Alerts (Immediate Response)
```yaml
# Service Down
- alert: JavaSleuthDown
  expr: up{job="java-sleuth"} == 0
  for: 1m
  severity: critical

# High Memory Usage
- alert: HighMemoryUsage
  expr: heap_usage_percent > 90
  for: 2m
  severity: critical

# High Error Rate
- alert: HighErrorRate
  expr: error_rate_percent > 10
  for: 5m
  severity: critical
```

#### Warning Alerts (Monitor Closely)
```yaml
# Elevated Memory Usage
- alert: ElevatedMemoryUsage
  expr: heap_usage_percent > 80
  for: 5m
  severity: warning

# Slow Response Times
- alert: SlowResponseTimes
  expr: avg_response_time_ms > 1000
  for: 5m
  severity: warning
```

### Monitoring Dashboard

#### Key Panels
1. **Service Health Overview**
   - Service status indicator
   - Response time graph
   - Error rate graph
   - Throughput graph

2. **Resource Utilization**
   - Memory usage (heap/non-heap)
   - CPU utilization
   - Network connections
   - Disk I/O

3. **Performance Metrics**
   - Command execution times
   - Cache hit rates
   - Thread pool utilization
   - GC performance

4. **Security Metrics**
   - Authentication attempts
   - Failed logins
   - Security violations
   - Audit events

---

## Maintenance Procedures

### Daily Maintenance (5 minutes)
```bash
#!/bin/bash
# Daily maintenance script

echo "=== Java-Sleuth Daily Health Check ==="
date

# Service status
echo "Service Status:"
systemctl is-active java-sleuth

# Quick health check
echo "Health Check:"
timeout 10 bash -c 'echo "health" | nc localhost 3658' || echo "FAILED"

# Memory usage
echo "Memory Usage:"
ps -p $(pgrep -f java-sleuth) -o %mem --no-headers | tr -d ' ' | sed 's/$/% used/'

# Recent errors
echo "Recent Errors (last 24h):"
grep -c "ERROR" /opt/java-sleuth/logs/sleuth.out || echo "0"

# Disk space
echo "Disk Space:"
df -h /opt/java-sleuth | tail -1 | awk '{print $5 " used"}'

echo "=== Daily Check Complete ==="
```

### Weekly Maintenance (15 minutes)
```bash
#!/bin/bash
# Weekly maintenance script

echo "=== Java-Sleuth Weekly Maintenance ==="

# Rotate logs if needed
find /opt/java-sleuth/logs -name "*.log" -size +100M -exec logrotate {} \;

# Clean old backup files
find /opt/java-sleuth/backup -name "*.bak" -mtime +30 -delete

# Performance report
echo "=== Performance Report ==="
echo "metrics" | nc localhost 3658

# Security audit
echo "=== Security Summary ==="
grep "SECURITY" /opt/java-sleuth/logs/sleuth-security.log | tail -10

# Update system packages (if approved)
# sudo apt update && sudo apt upgrade -y

echo "=== Weekly Maintenance Complete ==="
```

### Monthly Maintenance (30 minutes)
```bash
#!/bin/bash
# Monthly maintenance script

echo "=== Java-Sleuth Monthly Maintenance ==="

# Full backup
tar czf /backup/java-sleuth-backup-$(date +%Y%m%d).tar.gz /opt/java-sleuth/

# Performance benchmarking
./scripts/perf/performance-benchmark.sh

# Security review
echo "=== Security Review ==="
# Review user access
# Check SSL certificate expiration
# Audit configuration changes

# Capacity planning
echo "=== Capacity Planning ==="
# Analyze growth trends
# Resource utilization analysis
# Performance trending

echo "=== Monthly Maintenance Complete ==="
```

---

## Escalation Procedures

### Level 1: Operations Team (First Response)
**Responsibilities**:
- Initial incident response
- Basic troubleshooting
- Service restarts
- Log analysis

**Escalation Criteria**:
- Issue not resolved in 30 minutes
- Requires code changes
- Security incident

### Level 2: Engineering Team
**Responsibilities**:
- Advanced troubleshooting
- Configuration changes
- Performance tuning
- Bug fixes

**Escalation Criteria**:
- Issue not resolved in 2 hours
- Requires architectural changes
- Data loss risk

### Level 3: Architecture/Management
**Responsibilities**:
- Strategic decisions
- Resource allocation
- Major architectural changes
- Business impact assessment

### Communication Templates

#### Initial Incident Report
```
INCIDENT: Java-Sleuth Service Issue
SEVERITY: [SEV-1/2/3/4]
START TIME: [YYYY-MM-DD HH:MM UTC]
IMPACT: [Description of user impact]
STATUS: [Investigating/Mitigating/Resolved]
NEXT UPDATE: [Time of next update]

TIMELINE:
[HH:MM] - Issue detected
[HH:MM] - Investigation started
[HH:MM] - Initial diagnosis

ACTIONS TAKEN:
- [List of actions]

CURRENT FOCUS:
- [What's being worked on]
```

#### Incident Resolution
```
INCIDENT RESOLVED: Java-Sleuth Service Issue
SEVERITY: [SEV-1/2/3/4]
RESOLUTION TIME: [Total time to resolve]
ROOT CAUSE: [Brief description]

TIMELINE:
[Complete timeline of events]

ACTIONS TAKEN:
[All actions taken during incident]

ROOT CAUSE ANALYSIS:
[Detailed analysis of what went wrong]

PREVENTIVE MEASURES:
[What will be done to prevent recurrence]

POST-INCIDENT REVIEW:
[Date/time of planned review meeting]
```

---

## Reference Information

### Useful Commands

#### Service Management
```bash
# Service control
systemctl start java-sleuth
systemctl stop java-sleuth
systemctl restart java-sleuth
systemctl status java-sleuth
systemctl enable java-sleuth

# Manual control
/opt/java-sleuth/bin/sleuth-production.sh start
/opt/java-sleuth/bin/sleuth-production.sh stop
/opt/java-sleuth/bin/sleuth-production.sh restart
/opt/java-sleuth/bin/sleuth-production.sh status
```

#### Diagnostics
```bash
# Application diagnostics
echo "health" | nc localhost 3658
echo "status" | nc localhost 3658
echo "metrics" | nc localhost 3658
echo "memory" | nc localhost 3658

# JVM diagnostics
jps
jstat -gc $(pgrep -f java-sleuth)
jstack $(pgrep -f java-sleuth)
jmap -histo $(pgrep -f java-sleuth)
jcmd $(pgrep -f java-sleuth) help
```

#### Log Analysis
```bash
# Service logs
tail -f /opt/java-sleuth/logs/sleuth.out
journalctl -u java-sleuth -f

# Error analysis
grep -i error /opt/java-sleuth/logs/*.log
awk '/ERROR/ {print $1, $2, $NF}' /opt/java-sleuth/logs/sleuth.out

# Security logs
tail -f /opt/java-sleuth/logs/sleuth-security.log
grep "VIOLATION" /opt/java-sleuth/logs/sleuth-audit.log
```

### Configuration Files

#### Main Configuration
- **Application**: `/opt/java-sleuth/config/sleuth.properties`
- **JVM**: `/opt/java-sleuth/config/jvm.conf`
- **Logging**: `/opt/java-sleuth/config/logging.properties`
- **Systemd**: `/etc/systemd/system/java-sleuth.service`

#### Log Files
- **Application**: `/opt/java-sleuth/logs/sleuth.out`
- **Audit**: `/opt/java-sleuth/logs/sleuth-audit.log`
- **Security**: `/opt/java-sleuth/logs/sleuth-security.log`
- **GC**: `/opt/java-sleuth/logs/gc.log`

### Network Ports
- **3658**: Main service port
- **9999**: JMX monitoring port

### Default Credentials
- **Admin**: admin / sleuth_admin_2023!
- **Operator**: operator / sleuth_op_2023!
- **Viewer**: viewer / sleuth_view_2023!

*Note: Change these in production!*

---

## Appendix: Emergency Procedures

### Complete System Recovery

If all else fails and the system needs to be completely rebuilt:

1. **Preserve Data**
   ```bash
   # Backup logs and configuration
   tar czf emergency-backup-$(date +%Y%m%d-%H%M).tar.gz /opt/java-sleuth/
   ```

2. **Clean Installation**
   ```bash
   # Remove existing installation
   systemctl stop java-sleuth
   rm -rf /opt/java-sleuth/

   # Reinstall from scratch
   ./scripts/deploy/production-deploy.sh
   ```

3. **Restore Configuration**
   ```bash
   # Restore from backup
   tar xzf emergency-backup-*.tar.gz -C /opt/java-sleuth/ --strip-components=2
   ```

4. **Verify Recovery**
   ```bash
   # Test all functionality
   systemctl start java-sleuth
   ./monitor.sh
   echo "health" | nc localhost 3658
   ```

### Contact Information

#### On-Call Rotation
- **Primary**: [Name] - [Phone] - [Email]
- **Secondary**: [Name] - [Phone] - [Email]
- **Escalation**: [Manager] - [Phone] - [Email]

#### Team Contacts
- **Tech Lead**: [Name] - [Email]
- **DevOps**: [Name] - [Email]
- **Security**: [Name] - [Email]

#### External Contacts
- **System Admin**: [Name] - [Phone]
- **Network Team**: [Name] - [Phone]
- **Vendor Support**: [Number] - [Case Portal]

---

*This runbook should be updated after each incident and reviewed monthly.*
