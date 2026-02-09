# Task List: 协议收敛（移除 legacy 文本协议）

Directory: `helloagents/history/2026-02/202602081630_drop_legacy_protocol/`

---

## 1. Server Protocol
- [√] 1.1 移除 legacy text handler：删除 `TextClientCommandHandler/TextReplyChannel`
- [√] 1.2 `CommandClientHandler`：拒绝非 `CMD`/`STREAM` 请求（返回协议错误并关闭连接）
- [√] 1.3 `HandshakeNegotiator`：协商仅选择 framed/binary，不再回退 legacy
- [√] 1.4 `CommandReplyChannel`：移除 legacy END marker API，并清理实现类
- [√] 1.5 `CommandRequestExecutor`：移除 legacy END marker 发送逻辑；sync success 统一 `reply.end()`

## 2. Client (Launcher)
- [√] 2.1 HELLO/CONFIG：仅声明 framed/binary，移除 legacy 读写分支
- [√] 2.2 handshake 关闭时强制 framed（避免 binary 升级不可用导致异常）

## 3. Config & Docs
- [√] 3.1 删除 `protocol.text.end.marker.enabled` 默认配置与 `config show` 输出
- [√] 3.2 文档同步：README/wiki/ops guide 更新协议说明（仅 framed/binary）

## 4. Testing
- [√] 4.1 删除 legacy 协议相关单测
- [√] 4.2 `mvn test` 通过

