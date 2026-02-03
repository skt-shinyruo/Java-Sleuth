# security

## Purpose
输入校验、审计记录与权限控制基础能力。

## Module Overview
- **Responsibility:** InputValidator/AuditLogger/Auth/AuthZ/SecurityValidator
- **Status:** ✅Stable
- **Last Updated:** 2026-02-03

## Specifications

### Requirement: 命令安全校验
**Module:** security
阻止非法命令与敏感输出泄露。

#### Scenario: 命令校验与输出清洗
前置：收到命令  
- 校验命令与参数
- 清洗输出防注入

### Requirement: 会话认证与授权
**Module:** security
为每个连接建立角色与权限边界。

#### Scenario: 匿名会话与登录升级
前置：新连接建立  
- 默认关闭匿名 viewer（`security.anonymous.viewer=false`）
- 口令认证默认关闭（`security.auth.password.enabled=false`），且不再提供任何硬编码默认口令；如需启用需显式配置密码或使用环境变量
- 当 `security.mode=hmac` 时，连接会按 `security.hmac.session.role` 自举会话角色（免口令），并依赖请求签名作为“持有 secret”证明
- `auth` 命令用于在开启口令认证后升级会话角色
- 高危命令强制授权

### Requirement: 安全默认策略收敛（非回环绑定保护）
**Module:** security / command
避免“非回环绑定 + 明文控制”导致的远程命令暴露风险。

#### Scenario: 非回环 bind + security.mode=off 被拒绝启动
前置：`server.bind.address` 为非回环地址  
- 若 `security.mode=off`，服务端拒绝启动并提示修复方式  
- 若 `security.mode=hmac`，要求 `security.hmac.secret` 非空，否则同样拒绝启动

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

### Requirement: 可选请求签名与防重放（security.mode=hmac）
**Module:** security
默认启用 HMAC（`security.mode=hmac`），提供完整性校验与基础防重放能力；如需临时关闭需显式选择（例如 Launcher `--insecure`）。

#### Scenario: SIG 包装命令校验
前置：`security.mode=hmac`  
- 客户端以 `SIG v=2 ts=... nonce=... sid=<connId> sig=... cmd=...` 发送命令
- 服务端验证 HMAC-SHA256 签名
- 对 nonce 做去重与窗口期校验，拒绝重放
- 当 `protocol.handshake.enabled=true` 时，服务端会要求先完成 `HELLO/CONFIG` 握手，并强制使用 `v=2`（sid 绑定到握手协商的 connId）

### Requirement: 危险命令二次确认（防误触 + 可审计）
**Module:** security / command
对标“手术刀级别能力”的工程实践：危险命令默认需要一次性确认 token，降低误触与脚本误用风险。

#### Scenario: 危险命令需 --confirm <token>
前置：命令被标记为 dangerous（例如 redefine/retransform/mc/heapdump/reset/stop）  
- 首次执行：服务端返回挑战 token（不执行实际操作），并提示在 TTL 内追加 `--confirm <token>` 重试
- 二次执行：校验 token（一次性、过期失效），通过后才执行危险操作
- challenge/confirm 会写入审计日志，便于追溯
配置项：
- `security.dangerous.confirm.enabled`（默认 true）
- `security.dangerous.confirm.ttl.ms`
- `security.dangerous.confirm.token.bytes`
- `security.dangerous.confirm.cache.size`

### Requirement: 安全默认边界
**Module:** security
默认绑定 `server.bind.address=127.0.0.1`，降低默认口令与明文传输的暴露面。

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
- config

## Change History
- 202601281100_init_kb (planned)
- 202601281207_sleuth_plugin_stream (history/2026-01/202601281207_sleuth_plugin_stream/) - 会话认证与授权接入
- 202601281301_sleuth_handshake_secure_frames (history/2026-01/202601281301_sleuth_handshake_secure_frames/) - 可选 HMAC+nonce 防重放与协议安全默认
- 202601291031_fix-5-issues (history/2026-01/202601291031_fix-5-issues/) - 默认匿名策略收敛、非回环绑定保护、审计脱敏加强
- 202602011222_sleuth_hardening_bootstrap (history/2026-02/202602011222_sleuth_hardening_bootstrap/) - 移除硬编码口令、HMAC 会话自举与审计输出可控
- 202602021233_quality_audit_more_issues (history/2026-02/202602021233_quality_audit_more_issues/) - 危险命令标记/限流与关键安全边界单测补齐
