# Java-Sleuth 生产部署指南

## 目录

1. [概览](#overview)
2. [系统要求](#system-requirements)
3. [部署前检查清单](#pre-deployment-checklist)
4. [安装方式](#installation-methods)
5. [配置](#configuration)
6. [安全配置](#security-setup)
7. [监控与可观测性](#monitoring-and-observability)
8. [性能调优](#performance-tuning)
9. [故障排查](#troubleshooting)
10. [维护流程](#maintenance-procedures)
11. [容灾恢复](#disaster-recovery)

<a id="overview"></a>
## 概览

Java-Sleuth 是一个面向生产环境的 Java 诊断与监控 Agent，可为 JVM 应用提供实时洞察。本指南覆盖生产环境的完整部署流程。

### 核心特性
- ✅ 实时 JVM 监控与诊断
- ✅ 多级缓存带来的高级性能优化
- ✅ 认证与授权能力支撑的完整安全防护
- ✅ 通过 JMX 与指标采集实现的生产级监控
- ✅ 优雅的停止与重启机制
- ✅ 审计日志与安全事件追踪

<a id="system-requirements"></a>
## 系统要求

### 硬件要求

| 组件 | 最低 | 推荐 | 高负载 |
|-----------|---------|-------------|-----------|
| CPU | 2 核 | 4 核 | 8+ 核 |
| RAM | 4 GB | 8 GB | 16+ GB |
| 磁盘 | 10 GB | 50 GB | 100+ GB |
| 网络 | 100 Mbps | 1 Gbps | 10 Gbps |

### 软件要求

- **Java**：OpenJDK 8+ 或 Oracle JDK 8+
- **操作系统**：Linux（Ubuntu 18.04+、RHEL 7+、CentOS 7+）
- **网络**：端口 3658（默认）与 9999（JMX）可用
- **可选**：使用 systemd 进行服务管理

### JVM 要求

```bash
# Agent 最低堆配置
-Xms512m -Xmx2g

# 生产环境推荐
-Xms2g -Xmx4g

# 高负载环境
-Xms4g -Xmx8g
```

<a id="pre-deployment-checklist"></a>
## 部署前检查清单

### 基础设施准备

- [ ] 确认硬件满足最低要求
- [ ] 确保 Java 已安装且配置正确
- [ ] 检查网络连通性与防火墙规则
- [ ] 校验磁盘空间与 I/O 性能
- [ ] 配置系统限制（ulimits）
- [ ] 配置日志滚动（log rotation）
- [ ] 配置 NTP 进行时间同步

### 安全准备

- [ ] 为 Java-Sleuth 创建专用用户账号
- [ ] 配置 SSL 证书（如需外部访问）
- [ ] 配置防火墙规则
- [ ] 评审安全策略与合规要求
- [ ] 配置审计日志落地位置
- [ ] 配置入侵检测（如需要）

### 监控准备

- [ ] 准备监控基础设施（Prometheus、Grafana 等）
- [ ] 配置日志聚合（ELK、Splunk 等）
- [ ] 配置告警规则与通知渠道
- [ ] 准备常见场景的运维 Runbook
- [ ] 配置备份与保留策略

<a id="installation-methods"></a>
## 安装方式

### 方式 1：自动化部署脚本（推荐）

```bash
# 下载并执行生产部署脚本
./scripts/deploy/production-deploy.sh

# 按提示交互执行
# 脚本将完成：
# - 构建项目
# - 创建目录结构
# - 部署文件与配置
# - 配置 systemd 服务
# - 配置监控
```

### 方式 2：手工安装

#### 步骤 1：构建项目

```bash
# 克隆仓库
git clone https://github.com/your-org/java-sleuth.git
cd java-sleuth

# 使用 Maven 构建
mvn clean package -DskipTests

# 校验构建产物
ls -la core/target/java-sleuth-*-jar-with-dependencies.jar
```

#### 步骤 2：创建目录结构

```bash
# 创建安装目录
sudo mkdir -p /opt/java-sleuth/{bin,lib,config,logs,backup}

# 设置权限
sudo chown -R sleuth:sleuth /opt/java-sleuth
sudo chmod 755 /opt/java-sleuth
```

#### 步骤 3：部署文件

```bash
# 复制 JAR 文件
cp core/target/java-sleuth-*-jar-with-dependencies.jar /opt/java-sleuth/lib/

# 复制配置文件
cp config-templates/production-sleuth.properties /opt/java-sleuth/config/sleuth.properties
cp config-templates/jvm-production.conf /opt/java-sleuth/config/jvm.conf

# 复制启动脚本
cp sleuth.sh /opt/java-sleuth/bin/
chmod +x /opt/java-sleuth/bin/sleuth.sh
```

### 方式 3：容器化部署

#### Docker

```dockerfile
FROM openjdk:8-jre-alpine

COPY core/target/java-sleuth-*-jar-with-dependencies.jar /app/java-sleuth.jar
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

<a id="configuration"></a>
## 配置

### 核心配置文件

#### 1. 应用配置（`sleuth.properties`）

```properties
# 生产配置模板
server.bind.address=127.0.0.1
server.port=3658
server.max.connections=50
server.executor.queue.capacity=50
server.connection.timeout=60000

# 性能配置
performance.cache.ttl=10000
performance.thread.pool.core=8
performance.thread.pool.max=32
performance.command.executor.core=8
performance.command.executor.max=32
performance.command.executor.queue.capacity=200

# 安全配置
security.input.validation=true
security.audit.logging=true
security.allowed.commands=*

# 监控配置
monitoring.metrics.enabled=true
monitoring.health.checks=true
monitoring.jmx.enabled=true
```

#### 2. JVM 配置（`jvm.conf`）

```bash
# 内存设置
-Xms2g
-Xmx4g
-XX:MetaspaceSize=256m
-XX:MaxMetaspaceSize=512m

# GC 设置
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=16m

# 监控
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=9999
```

#### 3. 日志配置（`logging.properties`）

```properties
# Root logger 配置
.level=INFO
handlers=java.util.logging.FileHandler, java.util.logging.ConsoleHandler

# File handler
java.util.logging.FileHandler.pattern=/opt/java-sleuth/logs/sleuth-%g.log
java.util.logging.FileHandler.limit=10485760
java.util.logging.FileHandler.count=10
```

### 环境差异化配置

#### 开发环境

```properties
# 开发环境覆盖项
logging.level=DEBUG
security.input.validation=false
monitoring.metrics.enabled=true
```

#### 预发布（Staging）环境

```properties
# 预发布环境配置
server.max.connections=25
performance.cache.ttl=5000
security.input.validation=true
```

#### 生产环境

```properties
# 生产环境配置
server.max.connections=50
performance.cache.ttl=10000
security.input.validation=true
security.audit.logging=true
monitoring.metrics.enabled=true
```

<a id="security-setup"></a>
## 安全配置

### 网络安全

#### 防火墙配置

```bash
# 放通 Java-Sleuth 端口
sudo ufw allow 3658/tcp

# 放通 JMX 端口（仅限内网）
sudo ufw allow from 10.0.0.0/8 to any port 9999

# 启用防火墙
sudo ufw enable
```

#### SSL/TLS 配置

如需外部访问，建议配置 SSL 终止（SSL termination）：

```bash
# 生成自签证书（仅开发环境）
openssl req -x509 -newkey rsa:4096 -keyout sleuth-key.pem -out sleuth-cert.pem -days 365

# 生产环境请使用 CA 签发证书
# 建议通过反向代理（nginx/Apache）做 SSL 终止
```

### 认证与授权

#### 用户管理

```bash
# 创建专用用户
sudo useradd -r -s /bin/false sleuth
sudo usermod -aG sleuth sleuth

# 设置文件权限
sudo chown -R sleuth:sleuth /opt/java-sleuth
sudo chmod 750 /opt/java-sleuth/config
sudo chmod 640 /opt/java-sleuth/config/*.properties
```

#### 访问控制

在应用中配置基于角色的访问控制：

```properties
# 安全配置（以当前代码实现为准）
# - 当前版本未实现 security.authentication.enabled / security.session.timeout 这类配置项
# - 默认关闭匿名 viewer：仅在 off + viewer 会话场景会要求先 auth；推荐使用 hmac 会话自举
# - 非回环绑定时禁止 security.mode=off（会拒绝启动），建议启用 hmac 并设置强随机 secret
security.authorization.enabled=true
security.anonymous.viewer=false
security.mode=hmac
security.hmac.secret=<a-strong-random-secret>
# Loopback 自洽启动（生产建议明确配置 secret，并关闭打印）
security.hmac.secret.autogen.on.loopback=false
security.hmac.secret.autogen.print=false
security.hmac.timestamp.window.ms=30000
security.hmac.nonce.cache.size=10000
security.hmac.session.role=operator

# 危险命令二次确认（推荐）
security.dangerous.confirm.enabled=true
security.dangerous.confirm.ttl.ms=60000
security.dangerous.confirm.token.bytes=12
security.dangerous.confirm.cache.size=2000

# 高影响命令治理（推荐）
security.impact.high.confirm.enabled=true
security.impact.high.concurrent.limit=1

# 可选：口令认证（默认关闭）
security.auth.password.enabled=false
# security.auth.admin.password=<set-a-strong-password>
# security.auth.operator.password=<set-a-strong-password>
# security.auth.viewer.password=<set-a-strong-password>
```

### 审计日志

#### 审计配置

```properties
# 启用完整审计日志
security.audit.logging=true
logging.audit.enabled=true
logging.audit.console.enabled=false
# 默认文件路径：若留空，Java-Sleuth 将使用 java.io.tmpdir 并带 pid 后缀。
logging.audit.file.path=/opt/java-sleuth/logs/sleuth-audit.log
logging.security.file.path=/opt/java-sleuth/logs/sleuth-security.log
```

#### 日志监控

```bash
# 监控安全事件
tail -f /opt/java-sleuth/logs/sleuth-security.log

# 配置日志滚动
sudo logrotate -d /etc/logrotate.d/java-sleuth
```

<a id="monitoring-and-observability"></a>
## 监控与可观测性

### 健康检查

#### 内置健康检查

```bash
# NOTE:
# 推荐做法：使用 SleuthLauncher 连接后执行 health/status/metrics 等诊断命令（本机排障）。
./sleuth.sh
# sleuth> health
# sleuth> status
# sleuth> metrics
```

#### 外部监控集成

##### Prometheus 指标

```yaml
# prometheus.yml（示例）
scrape_configs:
  - job_name: 'java-sleuth'
    static_configs:
      - targets: ['localhost:9999']
    metrics_path: /metrics
    scrape_interval: 30s
```

##### Grafana 看板

```json
{
  "dashboard": {
    "title": "Java-Sleuth 监控",
    "panels": [
      {
        "title": "内存使用率",
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

### 日志管理

#### 集中式日志

```yaml
# ELK Stack 配置（示例）
filebeat.inputs:
- type: log
  paths:
    - /opt/java-sleuth/logs/*.log
  fields:
    service: java-sleuth
    environment: production
```

#### 日志分析

```bash
# 常用日志分析命令
grep "ERROR" /opt/java-sleuth/logs/sleuth-audit.log
grep "SECURITY" /opt/java-sleuth/logs/sleuth-security.log
awk '/WARNING/ {print $1, $2, $NF}' /opt/java-sleuth/logs/sleuth.log
```

### 告警规则

#### 内存告警

```yaml
# Prometheus 告警规则（示例）
groups:
- name: java-sleuth
  rules:
  - alert: HighMemoryUsage
    expr: heap_usage_percent > 85
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Java-Sleuth 内存使用率过高"
```

#### 性能告警

```yaml
- alert: HighResponseTime
  expr: command_avg_response_time > 1000
  for: 2m
  labels:
    severity: critical
  annotations:
    summary: "Java-Sleuth 响应时间过高"
```

<a id="performance-tuning"></a>
## 性能调优

### JVM 调优

#### 内存优化

```bash
# 适用于高吞吐场景
-Xms4g -Xmx8g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200

# 适用于低延迟场景
-Xms8g -Xmx8g
-XX:+UseZGC  # 需要 Java 11+
-XX:+UseLargePages
```

#### GC 调优

```bash
# G1GC 调优
-XX:G1HeapRegionSize=16m
-XX:G1NewSizePercent=30
-XX:G1MaxNewSizePercent=40
-XX:+G1UseAdaptiveIHOP

# Parallel GC 调优
-XX:+UseParallelGC
-XX:ParallelGCThreads=8
-XX:MaxGCPauseMillis=200
```

### 应用层调优

#### 缓存优化

```properties
# 根据负载调整缓存配置
performance.cache.ttl=5000    # 变化较快的数据
performance.cache.ttl=30000   # 相对稳定的数据
performance.cache.ttl=300000  # 静态数据
```

#### 线程池调优

```properties
# 根据 CPU 核数与负载调整
# 客户端连接处理线程（accept + per-connection IO）
performance.thread.pool.core=8   # CPU 核数
performance.thread.pool.max=32   # 4x CPU 核数

# 命令执行线程（非 stream 命令）
performance.command.executor.core=8
performance.command.executor.max=32
performance.command.executor.queue.capacity=200

# 连接接入背压
server.executor.queue.capacity=50
```

### 网络调优

#### TCP 设置

```bash
# 针对高吞吐场景的 TCP 参数优化
echo 'net.core.rmem_max = 16777216' >> /etc/sysctl.conf
echo 'net.core.wmem_max = 16777216' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_rmem = 4096 65536 16777216' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_wmem = 4096 65536 16777216' >> /etc/sysctl.conf
sysctl -p
```

#### 连接限制

```bash
# 提高连接数相关限制
echo 'sleuth soft nofile 65536' >> /etc/security/limits.conf
echo 'sleuth hard nofile 65536' >> /etc/security/limits.conf
```

<a id="troubleshooting"></a>
## 故障排查

### 常见问题与解决方案

#### 内存占用过高

**症状：**
- 日志出现 OutOfMemoryError
- 堆使用率告警
- 响应变慢

**排查：**
```bash
# 查看内存使用
./sleuth.sh
# sleuth> memory

# 生成 heap dump
jcmd <PID> GC.run_finalization
jcmd <PID> VM.gc
jmap -dump:live,format=b,file=heapdump.hprof <PID>

# 使用 Eclipse MAT 或 VisualVM 进行分析
```

**解决：**
- 增大堆：`-Xmx4g` → `-Xmx8g`
- 调整缓存 TTL
- 排查潜在内存泄漏
- 优化 GC 配置

#### 响应时间过高

**症状：**
- 命令执行慢
- 客户端超时
- CPU 使用率高

**排查：**
```bash
# 查看性能指标
./sleuth.sh
# sleuth> metrics

# 线程分析
jstack <PID> > thread-dump.txt

# 观察 GC
jstat -gc -t <PID> 5s
```

**解决：**
- 调整 GC 配置
- 增大线程池
- 优化缓存命中率
- 排查慢操作

#### 连接问题

**症状：**
- 无法连接 3658 端口
- Connection refused
- 间歇性连接失败

**排查：**
```bash
# 确认服务在运行
systemctl status java-sleuth

# 检查端口监听
netstat -tlnp | grep 3658

# 检查防火墙
sudo ufw status
```

**解决：**
- 重启服务：`systemctl restart java-sleuth`
- 检查防火墙规则
- 校验配置
- 排查端口冲突

### 诊断命令

#### 健康诊断

```bash
# 快速健康探测
./monitor.sh

# 详细状态
./sleuth.sh
# sleuth> status

# 查看 JVM 指标
jstat -gc <PID>
jinfo <PID>
```

#### 性能诊断

```bash
# 采样 30 秒
jcmd <PID> JFR.start duration=30s filename=profile.jfr

# 线程分析
jstack <PID>

# 内存分析
jmap -histo <PID>
```

#### 日志分析

```bash
# 错误分析
grep -i error /opt/java-sleuth/logs/*.log

# 安全事件
grep "SECURITY" /opt/java-sleuth/logs/sleuth-security.log

# 性能问题
grep "SLOW" /opt/java-sleuth/logs/sleuth.log
```

<a id="maintenance-procedures"></a>
## 维护流程

### 日常维护任务

#### 每日任务

- [ ] 检查服务健康与状态
- [ ] 浏览错误日志，发现问题趋势
- [ ] 监控内存与 CPU 使用率
- [ ] 确认备份成功

```bash
# 每日维护脚本
#!/bin/bash
echo "=== Java-Sleuth 每日维护 ==="
systemctl status java-sleuth
./monitor.sh
tail -50 /opt/java-sleuth/logs/sleuth.out | grep -i error
```

#### 每周任务

- [ ] 查看性能指标与趋势
- [ ] 分析 GC 日志，寻找优化空间
- [ ] 检查磁盘空间与日志滚动策略
- [ ] 更新安全补丁（如可用）

```bash
# 每周维护脚本
#!/bin/bash
echo "=== Java-Sleuth 每周维护 ==="
df -h /opt/java-sleuth
find /opt/java-sleuth/logs -name "*.log" -mtime +7 -ls
```

#### 每月任务

- [ ] 复核并更新配置
- [ ] 性能基准测试
- [ ] 安全审计与复盘
- [ ] 容量规划评估

### 更新与升级

#### 补丁升级（Patch Updates）

```bash
# 1. 下载新版本
wget https://releases.java-sleuth.com/vX.Y.Z/java-sleuth-X.Y.Z-jar-with-dependencies.jar

# 2. 备份当前版本
cp /opt/java-sleuth/lib/java-sleuth-*-jar-with-dependencies.jar /opt/java-sleuth/backup/

# 3. 停止服务
systemctl stop java-sleuth

# 4. 替换 JAR
cp java-sleuth-*-jar-with-dependencies.jar /opt/java-sleuth/lib/

# 5. 如有需要更新配置
# 请先阅读 release notes 中的配置变更说明

# 6. 启动服务
systemctl start java-sleuth

# 7. 验证部署
./monitor.sh
```

#### 大版本升级（Major Upgrades）

```bash
# 1. 全量备份
tar czf java-sleuth-backup-$(date +%Y%m%d).tar.gz /opt/java-sleuth/

# 2. 先在 staging 环境验证升级

# 3. 预约维护窗口

# 4. 按补丁升级流程操作，并增加额外验证

# 5. 升级后观察 24 小时
```

### 备份与恢复

#### 备份策略

```bash
# 配置备份
tar czf config-backup-$(date +%Y%m%d).tar.gz /opt/java-sleuth/config/

# 全量备份
rsync -av /opt/java-sleuth/ /backup/java-sleuth/

# 数据库备份（如适用）
# 应用状态通常是无状态的
```

#### 恢复流程

```bash
# 配置恢复
tar xzf config-backup-20231201.tar.gz -C /

# 全量恢复
systemctl stop java-sleuth
rsync -av /backup/java-sleuth/ /opt/java-sleuth/
systemctl start java-sleuth
```

<a id="disaster-recovery"></a>
## 容灾恢复

### 故障切换流程

#### 单实例故障

```bash
# 1. 发现故障
systemctl status java-sleuth

# 2. 尝试重启
systemctl restart java-sleuth

# 3. 重启失败则进一步排查
journalctl -u java-sleuth -f

# 4. 必要时从备份恢复
./restore-from-backup.sh
```

#### 全量系统故障

```bash
# 1. 准备新系统
# 2. 安装 Java 与依赖
# 3. 恢复配置与数据
tar xzf java-sleuth-backup-latest.tar.gz -C /opt/
# 4. 启动服务
systemctl enable java-sleuth
systemctl start java-sleuth
# 5. 验证功能
./monitor.sh
```

### 高可用配置

#### 负载均衡配置

```nginx
# nginx 配置
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

#### 集群配置

```properties
# Node 1 配置
server.port=3658
cluster.node.id=node1
cluster.peers=node2:3658,node3:3658

# Node 2 配置
server.port=3658
cluster.node.id=node2
cluster.peers=node1:3658,node3:3658
```

### 业务连续性

#### 沟通计划

1. **事件发现**：自动化监控告警
2. **升级矩阵**：
   - Level 1：运维团队
   - Level 2：工程团队
   - Level 3：管理层
3. **沟通渠道**：
   - Slack：#java-sleuth-ops
   - Email：ops@company.com
   - Phone：紧急值班轮转

#### 服务等级目标（SLOs）

- **可用性**：99.9% uptime
- **性能**：95% 的请求 < 100ms
- **恢复时间**：服务重启 < 15 分钟
- **恢复点**：最大数据丢失 < 1 小时

---

## 附录

### A. 命令参考

#### 管理类命令

```bash
# 服务管理
systemctl start java-sleuth
systemctl stop java-sleuth
systemctl restart java-sleuth
systemctl status java-sleuth

# 手工启动
/opt/java-sleuth/bin/sleuth-production.sh start
/opt/java-sleuth/bin/sleuth-production.sh stop
/opt/java-sleuth/bin/sleuth-production.sh status
```

#### 诊断类命令

```bash
# 健康与状态
./sleuth.sh
# sleuth> health
# sleuth> status
# sleuth> metrics

# JVM 诊断
jstat -gc <PID>
jstack <PID>
jmap -histo <PID>
```

### B. 配置参考

#### 完整配置模板

```properties
# 服务端配置
server.bind.address=127.0.0.1
server.port=3658
server.max.connections=50
server.executor.queue.capacity=50
server.connection.timeout=60000
server.socket.timeout=2000

# 性能配置
performance.cache.ttl=10000
performance.thread.pool.core=8
performance.thread.pool.max=32
performance.command.executor.core=8
performance.command.executor.max=32
performance.command.executor.queue.capacity=200
performance.command.timeout=120000
performance.command.timeout.max=300000
enhancement.failure.cooldown.ms=30000
enhancement.failure.log.interval.ms=60000

# Jobs 配置
jobs.max=200
jobs.ttl.ms=3600000
jobs.output.max.bytes=262144
jobs.max.running=4
jobs.queue.capacity=20

# 安全配置
security.input.validation=true
security.audit.logging=true
security.max.command.length=2000
security.allowed.commands=*
security.authorization.enabled=true
security.anonymous.viewer=false
security.mode=hmac
security.hmac.secret=<a-strong-random-secret>
security.hmac.secret.autogen.on.loopback=false
security.hmac.secret.autogen.print=false
security.hmac.timestamp.window.ms=30000
security.hmac.nonce.cache.size=10000
security.hmac.session.role=operator
security.dangerous.confirm.enabled=true
security.dangerous.confirm.ttl.ms=60000
security.dangerous.confirm.token.bytes=12
security.dangerous.confirm.cache.size=2000
security.impact.high.confirm.enabled=true
security.impact.high.concurrent.limit=1

# 监控配置
monitoring.metrics.enabled=true
monitoring.health.checks=true
monitoring.cache.cleanup.interval=300000
monitoring.jmx.enabled=true

# 日志配置
logging.level=INFO
logging.console.enabled=true
logging.audit.enabled=true
logging.performance.enabled=false

# 协议配置
protocol.mode=framed
protocol.streaming.enabled=true
protocol.frame.max.payload=4096
protocol.text.max.line.bytes=8192

# 插件配置
plugins.enabled=false
plugins.serviceloader.enabled=false
plugins.allowlist.sha256=
plugins.directory=/opt/java-sleuth/plugins
plugins.conflict.strategy=prefer-builtin
```

### C. 安全检查清单

- [ ] 已创建专用用户账号
- [ ] 文件权限设置正确
- [ ] 防火墙规则已配置
- [ ] 外部访问已配置 SSL/TLS
- [ ] 审计日志已启用
- [ ] 输入校验已启用
- [ ] JMX 访问已限制
- [ ] 已安排定期安全复审

### D. 性能基线

#### 预期性能指标

| 指标 | 目标 | 可接受 | 临界 |
|--------|--------|------------|----------|
| 内存使用率 | < 70% | < 85% | > 90% |
| 响应时间 | < 50ms | < 100ms | > 500ms |
| 吞吐量 | > 1000 req/s | > 500 req/s | < 100 req/s |
| 错误率 | < 0.1% | < 1% | > 5% |
| GC 暂停 | < 50ms | < 200ms | > 1s |

---

*本指南建议每季度复审更新一次，或在发生重大系统变更后及时更新。*
