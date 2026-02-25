# Change Proposal: 协议兼容逻辑彻底移除（仅保留新协议）

## Requirement Background
历史上存在 legacy 逐行文本回包方式（“写一行 + 换行”），且 sync 回包缺少显式结束边界；当响应内容包含换行（例如多行输出/堆栈/截断提示）时，客户端可能将一次响应误拆分为多条消息，导致粘包式错位。

当前已确认**不存在 legacy 客户端**，因此无需维持任何旧协议/旧配置/旧签名格式的兼容分支。为降低协议错位风险与维护复杂度，需要将协议与安全签名的实现**收敛为单一体系**并进行知识库同步。

## Change Content
1. 协议层：只允许新协议流程（HELLO/CONFIG 握手 + framed/binary），移除所有 legacy 相关提示与降级分支语义。
2. 安全层：`security.mode=hmac` 的签名格式收敛为**单一 SIG 格式**（必须携带并绑定 `sid`，且不再允许版本字段），移除旧版本兼容逻辑。
3. 客户端（Launcher）：握手与 binary upgrade 逻辑严格化，不再做任何“失败回退/降级兼容”。
4. 文档与知识库：同步更新协议/安全/配置说明与变更记录，确保以代码为准的 SSOT 一致。

## Impact Scope
- **Modules:** command / launcher / security / config / docs
- **Files:**
  - `core/src/main/java/com/javasleuth/command/server/CommandClientHandler.java`
  - `core/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`
  - `core/src/main/java/com/javasleuth/security/RequestSecurityManager.java`
  - `core/src/test/java/com/javasleuth/security/RequestSecurityManagerTest.java`
  - `helloagents/wiki/modules/security.md`
  - `helloagents/wiki/arch.md`
  - `helloagents/CHANGELOG.md`
  - `helloagents/history/index.md`
- **APIs:** N/A
- **Data:** N/A

## Core Scenarios

### Requirement: New Protocol Only (REQ-PROTO-ONLY)
**Module:** command/launcher
服务端连接必须先握手，命令仅允许 `CMD <signed_command>` / `STREAM <signed_command>`；任何非预期输入直接按协议错误处理，不提供 legacy 兼容或降级路径。

#### Scenario: 未握手直发命令/脏输入
客户端未发送 `HELLO` 就发送命令/随机内容
- 期望：服务端拒绝并返回握手/协议错误（并按策略关闭连接），不进入命令执行路径

### Requirement: Single SIG Format (REQ-SIG-SINGLE)
**Module:** security
当 `security.mode=hmac` 时，签名格式只允许单一实现：必须包含并绑定 `sid`（来自握手 `connId`），且不允许 `v` 等版本字段。

#### Scenario: `sid` 不一致或重复 nonce
签名 `sid` 与握手 `connId` 不一致，或同一 `sid` 下复用 nonce
- 期望：服务端拒绝请求，且 nonce 复用被判定为重放

## Risk Assessment
- **Risk:** 该变更会对所有旧实现/旧配置/旧签名格式产生破坏性影响（不再兼容）。
- **Mitigation:** 已确认无 legacy 客户端前提下，采用 fail-fast 策略；同时更新知识库，明确新协议与签名格式为唯一规范。

