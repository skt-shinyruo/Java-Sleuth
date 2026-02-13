# security

## Purpose
输入校验、审计记录与权限控制基础能力。

## Module Overview
- **Responsibility:** InputValidator/AuditLogger/Auth/AuthZ/SecurityValidator
- **Status:** ✅Stable
- **Last Updated:** 2026-02-13
- **Build Module:** foundation（低层基础模块）

## Specifications

### Requirement: 命令安全校验
**Module:** security
阻止非法命令与敏感输出泄露。

#### Scenario: 命令校验与输出清洗
前置：收到命令  
- 校验命令与参数
- 清洗输出防注入
- 流式命令输出同样统一走 `InputValidator.sanitizeOutput`（按 chunk），避免 watch/trace/tt/monitor/stack 绕过输出治理

### Requirement: 插件与文件路径安全边界一致性
**Module:** security / command / config
避免“配置宣称默认禁用/限制，但实现仍会加载或读取”的可预期性问题。

#### Scenario: 默认禁用插件时不加载 classpath provider
前置：`plugins.enabled=false`  
- 默认不加载目标进程 classpath 上的 `ServiceLoader` provider（需显式开启 `plugins.serviceloader.enabled=true`）

#### Scenario: 文件读取写入统一走 SecurityValidator
前置：命令需要访问文件  
- `mc` 读取源码与 `redefine` 读取 `.class` 前均通过 `SecurityValidator.canReadFile` 校验
- 产物写入（例如 `mc -o` 输出）通过 `SecurityValidator.canWriteFile` 校验

### Requirement: 会话认证与授权
#### Scenario: shutdown 时会话清理任务停止（避免后台线程残留）
前置：`AuthenticationManager` 会启动后台会话清理任务（daemon）  
- stop/detach 或服务端 shutdown 时，关闭编排会停止清理任务（幂等 best-effort）
- 再次 attach 时可重新启动清理任务（避免复用已 shutdown 的执行器）

#### Scenario: detach 时清理安全缓存（避免状态残留）
前置：同一 JVM 内发生 detach → re-attach  
- shutdown 编排会调用 `AuthorizationManager.shutdownInstance()`、`RequestSecurityManager.shutdownInstance()`、`DangerousCommandConfirmationManager.shutdownInstance()` 清空内部缓存并允许重新初始化
- 避免 rate limit/nonce/pending confirm 等状态跨 attach 残留，降低误判与不可预期行为

**Module:** security
为每个连接建立角色与权限边界。

#### Scenario: 匿名会话与登录升级
前置：新连接建立  
- 默认允许匿名 viewer（`security.anonymous.viewer=true`），并默认关闭 RBAC（`security.authorization.enabled=false`）
- 口令认证默认关闭（`security.auth.password.enabled=false`），且不再提供任何硬编码默认口令；如需启用需显式配置密码或使用环境变量
- 当显式启用 `security.mode=hmac` 时，连接会按 `security.hmac.session.role` 自举会话角色（免口令），并依赖请求签名作为“持有 secret”证明
- `auth` 命令用于在开启口令认证 + RBAC 后升级会话角色
- 高危命令强制授权

### Requirement: 安全默认策略收敛（非回环绑定保护）
**Module:** security / command
避免“非回环绑定 + 明文控制”导致的远程命令暴露风险。

#### Scenario: 非回环 bind + security.mode=off 被拒绝启动
前置：`server.bind.address` 为非回环地址  
- 若 `security.mode=off`，服务端拒绝启动并提示修复方式  
- 若 `security.mode=hmac`，要求 `security.hmac.secret` 非空，否则同样拒绝启动

#### Scenario: 回环 bind + security.mode=hmac 且 secret 为空的自洽启动
前置：`server.bind.address` 为回环地址（127.0.0.1/::1/localhost）  
- 若 `security.mode=hmac` 且 `security.hmac.secret` 为空：
  - 默认会自动生成临时 secret（避免“直接启动 Agent/服务端”失败；明文 secret 仅在交互控制台打印）
  - 可通过 `security.hmac.secret.autogen.on.loopback=false` 恢复严格拒绝策略
  - 生产环境建议显式配置强随机 secret，并关闭打印（`security.hmac.secret.autogen.print=false`）

### Requirement: 审计日志脱敏（password/secret/session）
**Module:** security
审计事件不应泄露口令、secret 或 bearer token。

#### Scenario: 审计事件不写入明文敏感信息
前置：开启 `security.audit.logging=true`  
- `auth` 命令的 password 以 `***` 记录  
- `config set` / `sysprop set` 对敏感 key 的 value 以 `***` 记录  
- sessionId 在结构化日志中也会被脱敏（避免 token 外泄）

### Requirement: 审计输出可控（文件路径/控制台）
**Module:** security
避免默认写入工作目录/刷屏 stdout/stderr，并允许在生产环境落盘到受控目录。

#### Scenario: 审计落盘与控制台输出可配置
前置：需要审计日志且希望可控输出  
- `logging.audit.console.enabled=false`（默认）避免污染目标 JVM stdout/stderr
- `logging.audit.file.path` / `logging.security.file.path` 可指定绝对路径
- 若路径留空：默认落到 `java.io.tmpdir` 并带 pid 后缀，降低权限/覆盖风险

补充约束（SSOT）：
- 控制台镜像输出走 stderr（不写 stdout），并且不依赖 `logging.level`/`logging.console.enabled` 的组合语义，避免“审计已开启但控制台无输出”的误判
- 具体实现：`AuditLogger` 在 `logging.audit.console.enabled=true` 时，通过 `SleuthLogger.auditConsole(...)` 镜像到控制台

### Requirement: 可选请求签名与防重放（security.mode=hmac）
**Module:** security
可选启用 HMAC（`security.mode=hmac`；默认 `security.mode=off`），提供完整性校验与基础防重放能力；需要时再显式开启。

#### Scenario: SIG 包装命令校验
前置：`security.mode=hmac`  
- 客户端以 `SIG ts=... nonce=... sid=<connId> sig=... cmd=...` 发送命令（不允许 `v` 字段）
- 服务端验证 HMAC-SHA256 签名
- 对 nonce 做去重与窗口期校验，拒绝重放
- `SIG` 行的 `k=v` 解析与握手 `HELLO/CONFIG` 解析统一走 `foundation` 的 `KvLineCodec`（SSOT），避免 client/server/security 三套解析逐渐分叉。

### Requirement: 高风险命令二次确认（防误触 + 可审计）
**Module:** security / command
对标“手术刀级别能力”的工程实践：危险命令与高影响命令支持一次性确认 token（默认关闭，可选开启），用于降低误触与脚本误用风险。

#### Scenario: 危险命令需 --confirm <token>
前置：命令被标记为 dangerous（例如 redefine/retransform/mc/heapdump/reset/stop）  
- 首次执行：服务端返回挑战 token（不执行实际操作），并提示在 TTL 内追加 `--confirm <token>` 重试
- 二次执行：校验 token（一次性、过期失效），通过后才执行危险操作
- challenge/confirm 会写入审计日志，便于追溯
配置项：
- `security.dangerous.confirm.enabled`（默认 false）
- `security.dangerous.confirm.ttl.ms`
- `security.dangerous.confirm.token.bytes`
- `security.dangerous.confirm.cache.size`

#### Scenario: 高影响命令（impact=HIGH）同样需要确认与并发限制
前置：命令 impact=HIGH（例如 heapdump/redefine/retransform/mc/reset/stop/jad/dump）  
- 首次执行：返回一次性确认 token（同上）
- 同一时刻仅允许有限并发（默认 1），避免多个重型操作叠加导致停顿/峰值
配置项：
- `security.impact.high.confirm.enabled`（默认 false）
- `security.impact.high.concurrent.limit`（默认 1）

### Requirement: 授权与危险命令元信息的 SSOT（CommandMeta）
**Module:** security（SSOT） / command（提供方）
权限校验、危险命令治理与审计标签依赖统一的“命令元信息”，避免出现“安全层维护一份映射、命令层维护另一份映射”导致漂移。

#### Scenario: security 不依赖 command 包也能完成治理
前置：命令被注册到 CommandRegistry  
- `command` 层在注册命令时必须提供对应 `CommandMeta`（内置命令内置 meta；插件命令不提供 meta 则拒绝注册）
- `AuthorizationManager` / `DangerousCommandConfirmationManager` 仅依赖 `CommandMeta` 入参完成校验与挑战/确认流程
- `CommandMeta` 位于低层（`com.javasleuth.security.CommandMeta`），以编译期边界避免 `security -> command` 反向依赖

### Requirement: 安全默认边界
**Module:** security
默认绑定 `server.bind.address=127.0.0.1`，降低默认口令与明文传输的暴露面。

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
- config
- util

## Change History
- 202601281100_init_kb (planned)
- 202601281207_sleuth_plugin_stream (history/2026-01/202601281207_sleuth_plugin_stream/) - 会话认证与授权接入
- 202601281301_sleuth_handshake_secure_frames (history/2026-01/202601281301_sleuth_handshake_secure_frames/) - 可选 HMAC+nonce 防重放与协议安全默认
- 202601291031_fix-5-issues (history/2026-01/202601291031_fix-5-issues/) - 默认匿名策略收敛、非回环绑定保护、审计脱敏加强
- 202602011222_sleuth_hardening_bootstrap (history/2026-02/202602011222_sleuth_hardening_bootstrap/) - 移除硬编码口令、HMAC 会话自举与审计输出可控
- 202602021233_quality_audit_more_issues (history/2026-02/202602021233_quality_audit_more_issues/) - 危险命令标记/限流与关键安全边界单测补齐
- 202602041158_unified_exec_pipeline (history/2026-02/202602041158_unified_exec_pipeline/) - 流式输出治理统一化、插件 ServiceLoader 默认禁用与文件路径校验一致性
- 202602051743_exception_handling_logging (history/2026-02/202602051743_exception_handling_logging/) - 审计控制台镜像语义收敛（stderr-only）+ 异常最小披露规范补充
- 202602081959_remove_compat_paths (history/2026-02/202602081959_remove_compat_paths/) - SIG 单一格式收敛（禁用 v 字段）与兼容路径清理
- 202602101815_layering_modularization (history/2026-02/202602101815_layering_modularization/) - CommandMeta 下沉到 security（SSOT）并消除 security->command 依赖环

## 2026-02-08 HMAC 签名强制升级

- 当 `security.mode=hmac` 时：只接受单一 `SIG` 格式（不允许 `v` 字段）。
- `SIG` 必须包含 `sid`，且必须与握手协商得到的 `connId` 一致（签名绑定）。
- 携带 `v` 字段或缺失 `sid` 的旧格式会被显式拒绝。
