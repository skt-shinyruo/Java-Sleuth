# Task List: 修复 streaming 协商一致性（服务端 SSOT）

Directory: `helloagents/history/2026-02/202602141515_streaming_negotiation_ssot/`

---

## 1. launcher / client
- [√] 1.1 修正 `launcher/src/main/java/com/javasleuth/launcher/client/ProtocolClient.java`：最终 streaming 以服务端 `CONFIG streaming=` 为准（修复“只增不减”），避免 client hint 覆盖 server=false

## 2. testing
- [√] 2.1 扩展 `launcher/src/test/java/com/javasleuth/launcher/client/ProtocolClientIntegrationTest.java`：覆盖 server `protocol.streaming.enabled=false` + client hint=true 的协商结果，并断言 `client.isStreamingEnabled()==false`

## 3. documentation
- [√] 3.1 更新 `helloagents/wiki/api.md`：明确 `CONFIG streaming=` 为 SSOT，`streaming=false` 时客户端必须退化为 `CMD ...`

## 4. verification
- [√] 4.1 运行 `mvn test`：构建与测试通过
