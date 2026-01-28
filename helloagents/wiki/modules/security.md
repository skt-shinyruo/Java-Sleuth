# security

## Purpose
输入校验、审计记录与权限控制基础能力。

## Module Overview
- **Responsibility:** InputValidator/AuditLogger/Auth/AuthZ/SecurityValidator
- **Status:** ✅Stable
- **Last Updated:** 2026-01-28

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
- 默认 viewer 角色会话
- auth 命令升级角色
- 高危命令强制授权

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
