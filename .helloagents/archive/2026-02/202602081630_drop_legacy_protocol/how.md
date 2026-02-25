# Technical Design: 协议收敛（移除 legacy 文本协议）

## Technical Solution

### Core Design
- **只保留两种协议：**
  - framed：文本分帧（`CMD`/`STREAM` 请求，`DATA/ERR/END` 响应）
  - binary：严格二进制帧（握手后 `UPGRADE BINARY` 切换）
- **不再支持 legacy：** 逐行文本模式不再作为可协商/可执行的协议分支存在。

### Server Side Changes
1. `HandshakeNegotiator`：
   - 默认协议选择为 framed（或按配置选择 binary）
   - 不再向 legacy 回退
2. `CommandClientHandler`：
   - 非 `CMD`/`STREAM` 前缀输入视为 legacy 请求，返回协议错误并关闭连接
   - 移除 text handler（`TextClientCommandHandler/TextReplyChannel`）
3. `CommandReplyChannel`：
   - 移除 legacy 专用的 `sendLegacyEndMarker()` 能力
4. `CommandRequestExecutor`：
   - 移除 legacy END marker 的发送逻辑
   - sync 成功路径统一 `reply.end()`（framed/binary 均具备 END 语义）

### Client Side Changes (Launcher)
- HELLO 仅声明 `protocols=binary,framed`
- 移除 legacy 写入与“短超时猜结束”读取逻辑
- 当握手关闭时，强制使用 framed（binary 依赖握手升级）

### Config/Docs
- 删除 legacy 专用配置 `protocol.text.end.marker.enabled`
- `protocol.mode` 仅允许 `framed|binary`（非法值归一化为 framed）

## Risks & Mitigations
- **风险：** 仍存在外部 legacy 客户端 → 连接会被拒绝
  - **缓解：** 明确在文档与错误提示中提示使用 framed/binary；默认握手开启便于协商参数
- **风险：** 协议收敛属于行为变更
  - **缓解：** 变更集中在协议入口与 Launcher，命令执行链路不改语义；通过单测回归

