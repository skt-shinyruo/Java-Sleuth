# Technical Design: 强制新协议（无旧实现/旧配置兼容）

## Protocol & Handshake
- 服务端强制握手：连接必须先发送 `HELLO`，协商仅限 `framed|binary`。
- 握手参数严格化：`HELLO` 必须包含 `connId`、`protocols`、`protocol` 等关键字段。

## Security (HMAC)
- `security.mode=hmac`：只允许 `SIG v=2`。
- `SIG` 必须包含 `sid`，并与握手协商的 `connId` 一致（v2 绑定）。
- 移除/拒绝 v1 兼容路径。

## Config Strictness
- `protocol.mode` 仅允许 `framed|binary`；非法值启动即失败（不再自动归一化）。
- 显式拒绝旧配置键：
  - `protocol.handshake.enabled`（握手已强制）
  - `protocol.text.end.marker.enabled`（legacy 已移除）

## Cleanup
- 移除 `FrameCodec` 的 PrintWriter legacy 写帧路径（仅保留 OutputStream 版本）。
- 移除 stack 命令的 legacy 子操作分支与接口实现。
