# Java-Sleuth 运维 Runbook

> 运维排障建议使用 SleuthLauncher（`./sleuth.sh`）连接后执行 `health/status/metrics/...`。

## 快速参考

### 紧急联系人
- **值班工程师**：+1-XXX-XXX-XXXX
- **团队负责人**：+1-XXX-XXX-XXXX
- **升级通道**：manager@company.com

### 关键命令
```bash
# 服务状态
systemctl status java-sleuth

# 紧急重启
systemctl restart java-sleuth

# 健康检查
./sleuth.sh
# sleuth> health

# 查看近期日志
tail -f /opt/java-sleuth/logs/sleuth.out
```

### 服务端点
- **主服务**：`localhost:3658`
- **JMX 监控**：`localhost:9999`
- **健康检查**：使用 SleuthLauncher 执行 `health`

---

## 事件响应流程

### 严重程度等级

#### SEV-1（严重）
**定义**：服务完全不可用  
**响应时间**：15 分钟  
**升级**：立即  

**症状**：
- 3658 端口不可达
- 健康检查持续失败
- 应用完全崩溃

**立即处理**：
1. **确认问题**（2 分钟）
   ```bash
   systemctl status java-sleuth
   nc -zv localhost 3658
   ```

2. **尝试重启服务**（3 分钟）
   ```bash
   systemctl restart java-sleuth
   sleep 30
   ./sleuth.sh
   # sleuth> health
   ```

3. **重启失败则检查日志**（5 分钟）
   ```bash
   journalctl -u java-sleuth --since "10 minutes ago"
   tail -100 /opt/java-sleuth/logs/sleuth.out
   ```

4. **15 分钟内未恢复则立即升级**

#### SEV-2（高）
**定义**：明显的性能退化  
**响应时间**：30 分钟  
**升级**：1 小时  

**症状**：
- 响应时间 > 5 秒
- 内存使用率 > 90%
- 错误率高（> 5%）

**处理**：
1. **检查系统资源**
   ```bash
   top -p $(pgrep -f java-sleuth)
   df -h /opt/java-sleuth
   ```

2. **检查 JVM 指标**
   ```bash
   jstat -gc $(pgrep -f java-sleuth)
   ./sleuth.sh
   # sleuth> metrics
   ```

3. **回顾近期变更**
   - 查看部署日志
   - 检查配置改动
   - 排查系统更新

#### SEV-3（中）
**定义**：不影响核心功能的小问题  
**响应时间**：2 小时  
**升级**：4 小时  

**症状**：
- 偶发慢响应
- 日志中出现非关键错误
- 轻微内存增长

#### SEV-4（低）
**定义**：监控告警、问题咨询  
**响应时间**：下一个工作日  
**升级**：无需  

---

## 常见场景与处理

### 场景 1：服务无法启动

**症状**：
- `systemctl start java-sleuth` 失败
- 退出码非 0
- systemd 日志出现错误

**排查步骤**：
```bash
# 查看服务状态
systemctl status java-sleuth

# 查看 systemd 日志
journalctl -u java-sleuth --since "1 hour ago"

# 查看应用日志
tail -50 /opt/java-sleuth/logs/sleuth.out

# 校验配置（文件级）
test -f /opt/java-sleuth/config/sleuth.properties
grep -nE '^(server\\.bind\\.address|server\\.port|security\\.authorization\\.enabled|security\\.auth\\.password\\.enabled|security\\.anonymous\\.viewer)=' /opt/java-sleuth/config/sleuth.properties

# 若服务已启动且可连接（推荐）：使用 SleuthLauncher 验证运行态关键项
# ./sleuth.sh
# sleuth> config show
# sleuth> status
# sleuth> health

# 检查文件权限
ls -la /opt/java-sleuth/
ls -la /opt/java-sleuth/config/
```

**常见原因与解决**：

1. **配置错误**
   ```bash
   # 检查配置语法
   grep -n "=" /opt/java-sleuth/config/sleuth.properties | grep -v "^#"

   # 必要时从备份恢复
   cp /opt/java-sleuth/backup/sleuth.properties.bak /opt/java-sleuth/config/sleuth.properties
   ```

2. **端口被占用**
   ```bash
   # 查看端口占用
   lsof -i :3658
   netstat -tlnp | grep 3658

   # 结束冲突进程或修改端口
   ```

3. **文件权限问题**
   ```bash
   # 修复权限
   sudo chown -R sleuth:sleuth /opt/java-sleuth
   sudo chmod 755 /opt/java-sleuth/bin/*.sh
   ```

4. **内存不足**
   ```bash
   # 查看可用内存
   free -h

   # 临时降低堆大小
   export JAVA_OPTS="-Xms512m -Xmx1g"
   ```

5. **安全模式配置不当**
   - 症状：日志包含 `SECURITY ERROR: Refusing to start ...`
   - 检查点：
     - Java-Sleuth 命令服务端为 **loopback-only**：非回环 bind（例如 `0.0.0.0` / 局域网 IP）会拒绝启动
     - 清理旧配置项：`security.mode`、`security.hmac.*`、`security.bootstrap.hmac.*`（这些 key 已移除/禁用，配置会 fail-fast）
     - 如需本机多用户权限控制：启用 `security.authorization.enabled=true` + `security.auth.password.enabled=true` 并设置强口令

### 场景 2：内存占用过高

**症状**：
- 内存使用率 > 85%
- 日志出现 OutOfMemoryError
- GC 变慢

**排查**：
```bash
# 查看当前内存使用
./sleuth.sh
# sleuth> memory

# 导出 heap dump
jcmd $(pgrep -f java-sleuth) GC.run_finalization
jmap -dump:live,format=b,file=/tmp/heapdump-$(date +%Y%m%d-%H%M).hprof $(pgrep -f java-sleuth)

# 分析 GC
jstat -gc -t $(pgrep -f java-sleuth) 5s 12

# 排查内存泄漏线索
jcmd $(pgrep -f java-sleuth) VM.classloader_stats
```

**立即处理**：
1. **触发垃圾回收**
   ```bash
   jcmd $(pgrep -f java-sleuth) GC.run
   ```

2. **清理缓存**
   ```bash
   # 清理活动增强/后台任务（危险，需 confirm token）
   ./sleuth.sh
   # sleuth> reset
   ```

3. **必要时重启服务**
   ```bash
   systemctl restart java-sleuth
   ```

**长期方案**：
- 增大堆：`-Xmx4g` → `-Xmx8g`
- 调整缓存 TTL
- 优化 GC 参数
- 排查潜在内存泄漏

### 场景 3：响应时间过高

**症状**：
- 命令执行耗时 > 1 秒
- 客户端超时
- 队列堆积

**排查**：
```bash
# 查看线程状态
jstack $(pgrep -f java-sleuth) > /tmp/threadump-$(date +%Y%m%d-%H%M).txt

# 查看性能指标
./sleuth.sh
# sleuth> metrics

# 实时观察性能
# NOTE: 默认启用握手（HELLO），无法用 nc 直接轮询 status（但可用于端口连通性探测）。
# 建议通过 JMX/监控系统观测，或在 SleuthLauncher 中手动多次执行 `status`。

# 查看系统负载
top -p $(pgrep -f java-sleuth)
iostat -x 1 5
```

**处理**：
1. **检查线程死锁**
   ```bash
   jstack $(pgrep -f java-sleuth) | grep -A 5 -B 5 "BLOCKED"
   ```

2. **临时调大线程池**
   ```bash
   # 更新配置
   echo "performance.thread.pool.max=64" >> /opt/java-sleuth/config/sleuth.properties
   systemctl restart java-sleuth
   ```

3. **排除性能瓶颈**
   ```bash
   # 缓存命中率过低时可考虑清理
   # 清理活动增强/后台任务（危险，需 confirm token）
   ./sleuth.sh
   # sleuth> reset
   ```

### 场景 4：连接问题

**症状**：
- 无法连接 3658 端口
- Connection refused
- 间歇性连接失败
- 连接建立后立即返回 `ERROR: server busy ...`（服务端过载保护触发）

**排查**：
```bash
# 确认服务在运行
systemctl status java-sleuth
ps aux | grep java-sleuth

# 检查端口监听
netstat -tlnp | grep 3658
lsof -i :3658

# 测试连通性
nc -zv localhost 3658
telnet localhost 3658

# 检查防火墙
sudo ufw status
iptables -L | grep 3658
```

**处理**：
1. **必要时重启服务**
   ```bash
   systemctl restart java-sleuth
   ```

2. **检查防火墙规则**
   ```bash
   sudo ufw allow 3658/tcp
   sudo ufw reload
   ```

3. **验证配置**
   ```bash
   grep "server.port" /opt/java-sleuth/config/sleuth.properties
   ```

4. **过载时调参：背压/线程池**
   - 连接侧过载：调大 `server.executor.queue.capacity` 或降低 `server.max.connections`
   - 命令侧过载：调大 `performance.command.executor.queue.capacity` / `performance.command.executor.max`
   - 生产建议：保持 `security.impact.high.concurrent.limit=1`，避免高影响命令并发导致停顿/峰值叠加

### 场景 5：安全告警

**症状**：
- 安全违规日志
- 异常认证尝试
- 可疑命令模式

**排查**：
```bash
# 查看安全日志
tail -100 /opt/java-sleuth/logs/sleuth-security.log

# 搜索安全违规
grep "SECURITY_VIOLATION" /opt/java-sleuth/logs/sleuth-audit.log

# 查看认证失败
grep "AUTHENTICATION_FAILED" /opt/java-sleuth/logs/sleuth-audit.log

# 查看近期连接
grep "CONNECTION" /opt/java-sleuth/logs/sleuth-audit.log | tail -20
```

**立即处理**：
1. **封禁可疑 IP**
   ```bash
   sudo ufw deny from SUSPICIOUS_IP
   ```

2. **必要时进入紧急收敛**
   ```bash
   # 推荐：通过运行时配置快速收敛允许的命令集合（需 ADMIN 权限）
   ./sleuth.sh
   # sleuth> config set security.allowed.commands health,status,metrics

   # 兜底：直接停止 agent（危险命令，需 confirm token）
   # sleuth> stop
   ```

3. **回溯审计轨迹**
   ```bash
   awk '/SECURITY/ {print $1, $2, $NF}' /opt/java-sleuth/logs/sleuth-audit.log
   ```

---

## 监控与告警

### 需要关注的关键指标

#### 性能指标
- **响应时间**：目标 < 100ms，告警 > 500ms
- **吞吐量**：目标 > 1000 req/s，告警 < 100 req/s
- **错误率**：目标 < 0.1%，告警 > 5%
- **缓存命中率**：目标 > 80%，告警 < 50%

#### 资源指标
- **内存使用率**：目标 < 70%，告警 > 85%
- **CPU 使用率**：目标 < 60%，告警 > 80%
- **磁盘使用率**：目标 < 70%，告警 > 85%
- **网络连接数**：目标 < 最大值的 80%，告警 > 90%

#### 可用性指标
- **服务可用性**：目标 99.9%，告警 < 99%
- **健康检查成功率**：目标 100%，告警 < 95%

### 告警定义

#### 严重告警（需立即响应）
```yaml
# 服务不可用
- alert: JavaSleuthDown
  expr: up{job="java-sleuth"} == 0
  for: 1m
  severity: critical

# 内存使用过高
- alert: HighMemoryUsage
  expr: heap_usage_percent > 90
  for: 2m
  severity: critical

# 错误率过高
- alert: HighErrorRate
  expr: error_rate_percent > 10
  for: 5m
  severity: critical
```

#### 警告告警（重点关注）
```yaml
# 内存使用升高
- alert: ElevatedMemoryUsage
  expr: heap_usage_percent > 80
  for: 5m
  severity: warning

# 响应变慢
- alert: SlowResponseTimes
  expr: avg_response_time_ms > 1000
  for: 5m
  severity: warning
```

### 监控看板

#### 关键面板
1. **服务健康概览**
   - 服务状态指示
   - 响应时间曲线
   - 错误率曲线
   - 吞吐量曲线

2. **资源利用率**
   - 内存使用（堆/非堆）
   - CPU 使用率
   - 网络连接数
   - 磁盘 I/O

3. **性能指标**
   - 命令执行耗时
   - 缓存命中率
   - 线程池利用率
   - GC 表现

4. **安全指标**
   - 认证尝试次数
   - 失败登录次数
   - 安全违规次数
   - 审计事件

---

## 维护流程

### 每日维护（5 分钟）
```bash
#!/bin/bash
# 每日维护脚本

echo "=== Java-Sleuth 每日健康检查 ==="
date

# 服务状态
echo "服务状态："
systemctl is-active java-sleuth

# 快速健康探测
echo "健康检查："
# 默认启用握手（HELLO），无法用 nc 直接发命令；这里用端口连通性作为快速探测。
timeout 2 nc -zv localhost 3658 || echo "失败"

# 内存使用
echo "内存使用："
ps -p $(pgrep -f java-sleuth) -o %mem --no-headers | tr -d ' ' | sed 's/$/% used/'

# 近期错误
echo "近 24 小时错误数："
grep -c "ERROR" /opt/java-sleuth/logs/sleuth.out || echo "0"

# 磁盘空间
echo "磁盘空间："
df -h /opt/java-sleuth | tail -1 | awk '{print $5 " used"}'

echo "=== 每日检查完成 ==="
```

### 每周维护（15 分钟）
```bash
#!/bin/bash
# 每周维护脚本

echo "=== Java-Sleuth 每周维护 ==="

# 必要时滚动日志
find /opt/java-sleuth/logs -name "*.log" -size +100M -exec logrotate {} \;

# 清理过期备份
find /opt/java-sleuth/backup -name "*.bak" -mtime +30 -delete

# 性能报告
echo "=== 性能报告 ==="
# 建议通过 JMX/监控系统获取 metrics；如需命令级诊断，请用 SleuthLauncher：
# ./sleuth.sh
# sleuth> metrics

# 安全审计
echo "=== 安全摘要 ==="
grep "SECURITY" /opt/java-sleuth/logs/sleuth-security.log | tail -10

# 系统包更新（如已审批）
# sudo apt update && sudo apt upgrade -y

echo "=== 每周维护完成 ==="
```

### 每月维护（30 分钟）
```bash
#!/bin/bash
# 每月维护脚本

echo "=== Java-Sleuth 每月维护 ==="

# 全量备份
tar czf /backup/java-sleuth-backup-$(date +%Y%m%d).tar.gz /opt/java-sleuth/

# 性能基准测试
./scripts/perf/performance-benchmark.sh

# 安全复盘
echo "=== 安全复盘 ==="
# 复查用户访问
# 检查 SSL 证书过期时间
# 审计配置变更

# 容量规划
echo "=== 容量规划 ==="
# 分析增长趋势
# 资源利用率分析
# 性能趋势分析

echo "=== 每月维护完成 ==="
```

---

## 升级流程

### Level 1：运维团队（第一响应）
**职责**：
- 初步事件响应
- 基础排障
- 服务重启
- 日志分析

**升级条件**：
- 30 分钟内未解决
- 需要代码改动
- 安全事件

### Level 2：工程团队
**职责**：
- 高级排障
- 配置改动
- 性能调优
- Bug 修复

**升级条件**：
- 2 小时内未解决
- 需要架构级改动
- 存在数据丢失风险

### Level 3：架构/管理层
**职责**：
- 战略决策
- 资源协调
- 重大架构调整
- 业务影响评估

### 沟通模板

#### 初始事件报告
```
事件：Java-Sleuth 服务问题
严重级别：[SEV-1/2/3/4]
开始时间：[YYYY-MM-DD HH:MM UTC]
影响范围：[用户影响描述]
状态：[排查中/缓解中/已解决]
下次更新：[下一次更新时间]

时间线：
[HH:MM] - 发现问题
[HH:MM] - 开始排查
[HH:MM] - 初步诊断

已采取行动：
- [行动列表]

当前关注点：
- [正在处理的事项]
```

#### 事件解决报告
```
事件已解决：Java-Sleuth 服务问题
严重级别：[SEV-1/2/3/4]
解决耗时：[总耗时]
根因概述：[简要描述]

时间线：
[完整时间线]

已采取行动：
[事件期间的所有行动]

根因分析：
[详细说明问题原因]

预防措施：
[避免复发的措施]

复盘安排：
[计划复盘会议的日期/时间]
```

---

## 参考信息

### 常用命令

#### 服务管理
```bash
# 服务控制
systemctl start java-sleuth
systemctl stop java-sleuth
systemctl restart java-sleuth
systemctl status java-sleuth
systemctl enable java-sleuth

# 手工控制
/opt/java-sleuth/bin/sleuth-production.sh start
/opt/java-sleuth/bin/sleuth-production.sh stop
/opt/java-sleuth/bin/sleuth-production.sh restart
/opt/java-sleuth/bin/sleuth-production.sh status
```

#### 诊断
```bash
# 应用诊断（建议使用 SleuthLauncher）
./sleuth.sh
# sleuth> health
# sleuth> status
# sleuth> metrics
# sleuth> memory

# JVM 诊断
jps
jstat -gc $(pgrep -f java-sleuth)
jstack $(pgrep -f java-sleuth)
jmap -histo $(pgrep -f java-sleuth)
jcmd $(pgrep -f java-sleuth) help
```

#### 日志分析
```bash
# 服务日志
tail -f /opt/java-sleuth/logs/sleuth.out
journalctl -u java-sleuth -f

# 错误分析
grep -i error /opt/java-sleuth/logs/*.log
awk '/ERROR/ {print $1, $2, $NF}' /opt/java-sleuth/logs/sleuth.out

# 安全日志
tail -f /opt/java-sleuth/logs/sleuth-security.log
grep "VIOLATION" /opt/java-sleuth/logs/sleuth-audit.log
```

### 配置文件

#### 主配置
- **应用**：`/opt/java-sleuth/config/sleuth.properties`
- **JVM**：`/opt/java-sleuth/config/jvm.conf`
- **日志**：`/opt/java-sleuth/config/logging.properties`
- **Systemd**：`/etc/systemd/system/java-sleuth.service`

#### 日志文件
- **应用**：`/opt/java-sleuth/logs/sleuth.out`
- **审计**：`/opt/java-sleuth/logs/sleuth-audit.log`
- **安全**：`/opt/java-sleuth/logs/sleuth-security.log`
- **GC**：`/opt/java-sleuth/logs/gc.log`

### 网络端口
- **3658**：主服务端口
- **9999**：JMX 监控端口

### 默认凭据
- **Admin**：admin / sleuth_admin_2023!
- **Operator**：operator / sleuth_op_2023!
- **Viewer**：viewer / sleuth_view_2023!

*注意：生产环境务必修改这些默认凭据！*

---

## 附录：紧急操作

### 完整系统恢复

如果其他方法均无效，且需要完全重建系统：

1. **保留数据**
   ```bash
   # 备份日志与配置
   tar czf emergency-backup-$(date +%Y%m%d-%H%M).tar.gz /opt/java-sleuth/
   ```

2. **清理并重新安装**
   ```bash
   # 移除现有安装
   # Java-Sleuth 为交互式 attach 工具（非独立常驻服务）；如在运行中，请先结束当前运维会话
   rm -rf /opt/java-sleuth/

   # 从头安装
   ./scripts/deploy/production-deploy.sh
   ```

3. **恢复配置**
   ```bash
   # 从备份恢复
   tar xzf emergency-backup-*.tar.gz -C /opt/java-sleuth/ --strip-components=2
   ```

4. **验证恢复**
   ```bash
   # 验证功能
   /opt/java-sleuth/bin/monitor.sh
   /opt/java-sleuth/bin/sleuth-production.sh
   # sleuth> health
   ```

### 联系方式

#### 值班轮转
- **Primary**：[Name] - [Phone] - [Email]
- **Secondary**：[Name] - [Phone] - [Email]
- **Escalation**：[Manager] - [Phone] - [Email]

#### 团队联系人
- **Tech Lead**：[Name] - [Email]
- **DevOps**：[Name] - [Email]
- **Security**：[Name] - [Email]

#### 外部联系人
- **System Admin**：[Name] - [Phone]
- **Network Team**：[Name] - [Phone]
- **Vendor Support**：[Number] - [Case Portal]

---

*Runbook 建议在每次事件后更新，并每月例行复审一次。*
