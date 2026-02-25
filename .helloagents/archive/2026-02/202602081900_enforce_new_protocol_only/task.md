# Task List: 强制新协议（无旧实现/旧配置兼容）

Directory: `helloagents/history/2026-02/202602081900_enforce_new_protocol_only/`

---

- [√] 强制握手流程（服务端拒绝未握手命令）
- [√] 严格 HELLO 参数（connId/protocol/protocols 必填）
- [√] `security.mode=hmac` 强制 `SIG v=2` + `sid` 绑定
- [√] 严格配置：拒绝旧配置键，`protocol.mode` 非法值启动失败
- [√] 清理 legacy 代码路径（FrameCodec PrintWriter / stack legacy ops）
- [√] 文档与知识库同步
- [√] 回归测试：`mvn test`
