# security

## Purpose
输入校验、审计记录与权限控制基础能力。

## Module Overview
- **Responsibility:** InputValidator/AuditLogger/Auth/AuthZ/SecurityValidator
- **Status:** ✅Stable
- **Last Updated:** 2026-01-29

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
- 默认关闭匿名 viewer（`security.anonymous.viewer=false`），连接后需要先执行 `auth`
- auth 命令升级角色
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

### Requirement: 可选请求签名与防重放（security.mode=hmac）
**Module:** security
在不改变默认兼容行为（security.mode=off）的前提下，提供可选的完整性校验与基础防重放能力。

#### Scenario: SIG 包装命令校验
前置：security.mode=hmac  
- 客户端以 `SIG ts=... nonce=... sig=... cmd=...` 发送命令
- 服务端验证 HMAC-SHA256 签名
- 对 nonce 做去重与窗口期校验，拒绝重放

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
