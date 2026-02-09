# Change Proposal: 强制新协议（无旧实现/旧配置兼容）

## Requirement
在明确不存在 legacy 客户端的前提下，继续保留任何兼容/降级路径会引入：
- 协议边界歧义与实现分支复杂度
- 配置项理解成本与误用风险
- 安全签名版本混用导致的校验不一致

本变更目标：**彻底不兼容任何旧实现/旧配置**，只保留明确边界的新协议能力。

## Success Criteria
- 握手为强制流程；未握手连接无法发送命令
- `security.mode=hmac` 下只接受 `SIG v=2`，且必须携带并绑定 `sid`
- 旧配置键（如 `protocol.handshake.enabled`、`protocol.text.end.marker.enabled`）被显式拒绝
- 测试通过（`mvn test`）
