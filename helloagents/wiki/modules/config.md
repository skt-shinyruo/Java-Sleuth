# config

## Purpose
统一加载并管理运行时配置。

## Module Overview
- **Responsibility:** 默认配置、外部配置、系统属性覆盖
- **Status:** ✅Stable
- **Last Updated:** 2026-02-04

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
- `server.executor.queue.capacity`：连接处理线程池排队上限（用于背压与内存上限控制）
- `protocol.handshake.enabled`：默认 true
- `protocol.mode`：legacy|framed|binary（默认 framed）
- `protocol.text.max.line.bytes`：文本协议单行最大字节数上限
- `protocol.text.end.marker.enabled`：legacy 文本协议流式输出是否追加 END marker（降低“超时猜结束”截断风险）
- `protocol.frame.max.payload`：分帧/二进制协议单帧 payload 最大字节数
- `security.mode`：off|hmac（默认 hmac）
- `security.anonymous.viewer`：默认 false（仅当会话角色为 viewer 时生效；hmac 模式下默认自举为 operator）
- `security.bootstrap.hmac.on.attach` / `security.bootstrap.hmac.secret.bytes`：Launcher attach 时 HMAC 自举开关与 secret 长度
- `security.hmac.session.role`：HMAC 模式下新连接的自举会话角色（viewer|operator|admin）
- `security.hmac.secret.autogen.on.loopback` / `security.hmac.secret.autogen.print`：loopback 下空 secret 自洽启动（自动生成临时 secret + 是否打印）
- `security.auth.password.enabled`：口令认证开关（默认 false）
- `security.auth.{admin|operator|viewer}.password`：口令认证密码（也可用环境变量 `SLEUTH_AUTH_*_PASSWORD`）
- `security.hmac.*`：HMAC 签名与防重放参数
- `security.dangerous.confirm.*`：危险命令二次确认（一次性 token + TTL）
- `security.impact.high.confirm.enabled` / `security.impact.high.concurrent.limit`：高影响命令治理（二次确认 + 并发限制）
- `performance.command.timeout`：命令执行超时
- `performance.command.timeout.max`：命令超时的上限护栏（避免被 runtime config 放大到不可控）
- `performance.command.executor.core` / `performance.command.executor.max` / `performance.command.executor.queue.capacity`：命令执行线程池与队列上限（避免 `newCachedThreadPool` 线程膨胀）
- `performance.maintenance.force_gc`：维护线程是否强制 `System.gc()`（默认 false）
- `enhancement.failure.cooldown.ms` / `enhancement.failure.log.interval.ms`：插桩失败冷却与日志限频（失败可重试，避免静默失效）
- `jobs.max` / `jobs.ttl.ms` / `jobs.output.max.bytes`：后台任务（jobs/流式命令）保留数量、TTL 与单任务输出上限
- `jobs.max.running` / `jobs.queue.capacity`：后台任务并发硬上限与队列上限（背压）
- `plugins.enabled`：插件目录加载开关（默认 false）
- `plugins.serviceloader.enabled`：是否允许从目标进程 classpath 通过 ServiceLoader 加载 CommandProvider（默认 false）
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

#### Scenario: config save 持久化（可选）运行时覆盖
前置：需要把临时调试配置固化到配置文件  
- `config save`：仅保存文件配置（properties），不包含 runtime overrides
- `config save --include-runtime`：合并保存 runtime overrides（覆盖同名 key），便于“调试 -> 固化 -> 重启验证”

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
- 202602021233_quality_audit_more_issues (history/2026-02/202602021233_quality_audit_more_issues/) - 默认配置/生产模板对齐与协议上限文档补齐
- 202602041158_unified_exec_pipeline (history/2026-02/202602041158_unified_exec_pipeline/) - jobs 并发上限、END marker、插件 ServiceLoader 开关与 config save 持久化语义补齐
