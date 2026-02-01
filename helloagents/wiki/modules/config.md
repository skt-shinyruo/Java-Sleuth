# config

## Purpose
统一加载并管理运行时配置。

## Module Overview
- **Responsibility:** 默认配置、外部配置、系统属性覆盖
- **Status:** ✅Stable
- **Last Updated:** 2026-02-01

## Specifications

### Requirement: 配置加载优先级
**Module:** config
默认配置 → 外部文件 → 系统属性覆盖（支持运行时读取 `-Dsleuth.<key>`）。

#### Scenario: 启动时读取配置
前置：系统启动  
- 读取 /sleuth-default.properties
- 读取 sleuth.properties（若存在，或通过 `-Dsleuth.config.file=/path/to/sleuth.properties` 指定）
- 读取 -Dsleuth.* 覆盖

### Requirement: 安全/协议新增配置项
**Module:** config
支持以下新增配置项（均可通过 `sleuth.properties` 或 `-Dsleuth.*` 设置）：
- `server.bind.address`：默认 127.0.0.1
- `server.max.connections`：并发连接上限（默认 10）
- `protocol.handshake.enabled`：默认 true
- `protocol.mode`：legacy|framed|binary
- `protocol.text.max.line.bytes`：文本协议单行最大字节数上限
- `security.mode`：off|hmac（默认 off）
- `security.anonymous.viewer`：默认 false（要求先 auth）
- `security.bootstrap.hmac.on.attach` / `security.bootstrap.hmac.secret.bytes`：Launcher attach 时 HMAC 自举开关与 secret 长度
- `security.hmac.session.role`：HMAC 模式下新连接的自举会话角色（viewer|operator|admin）
- `security.auth.password.enabled`：口令认证开关（默认 false）
- `security.auth.{admin|operator|viewer}.password`：口令认证密码（也可用环境变量 `SLEUTH_AUTH_*_PASSWORD`）
- `security.hmac.*`：HMAC 签名与防重放参数
- `performance.command.timeout`：命令执行超时
- `performance.maintenance.force_gc`：维护线程是否强制 `System.gc()`（默认 false）
- `plugins.enabled`：插件目录加载开关（默认 false）
- `plugins.allowlist.sha256`：插件 allowlist（jarName:sha256hex）
- `logging.audit.console.enabled`：审计事件是否镜像到控制台（默认 false）
- `logging.audit.file.path` / `logging.security.file.path`：审计/安全日志落盘路径（留空默认落到 tmp 并带 pid 后缀）
- `logging.performance.enabled`：是否启用性能/健康相关 stdout/stderr 日志（默认 false）
- `monitoring.trace.sample.rate`：trace 采样率（默认更保守）
- `monitoring.monitor.sample.rate`：monitor 采样率（默认 1.0，避免与 trace 误联动）
- `monitoring.watch.queue.capacity` / `monitoring.watch.drop.on.full`：watch 事件队列容量与满队列策略
- `monitoring.trace.queue.capacity` / `monitoring.trace.drop.on.full`：trace 事件队列容量与满队列策略

### Requirement: 运行时覆盖（Runtime Overrides）
**Module:** config
支持通过命令在运行时覆写部分配置项（优先级高于默认配置与外部文件），用于临时调试与回滚。

#### Scenario: config set/get 生效
前置：已连接并具备足够权限（建议 ADMIN）  
- `config set <key> <value>` 写入运行时覆盖  
- 读取配置时优先使用运行时覆盖（其次系统属性，再其次文件配置）  
- 对敏感 key 的 value 输出会自动脱敏

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
N/A

## Change History
- 202601281100_init_kb (planned)
- 202601281301_sleuth_handshake_secure_frames (history/2026-01/202601281301_sleuth_handshake_secure_frames/) - 新增 bind/handshake/security.mode 配置项
- 202601291031_fix-5-issues (history/2026-01/202601291031_fix-5-issues/) - 安全默认/资源治理/运行时覆写扩展与文档一致性修复
- 202602011222_sleuth_hardening_bootstrap (history/2026-02/202602011222_sleuth_hardening_bootstrap/) - 启动稳定化与安全自举相关配置项补齐
- 202602011706_core_fixes_java8_jad_session_regex_trace (history/2026-02/202602011706_core_fixes_java8_jad_session_regex_trace/) - logging.performance.enabled 默认关闭与监控队列策略文档补齐
