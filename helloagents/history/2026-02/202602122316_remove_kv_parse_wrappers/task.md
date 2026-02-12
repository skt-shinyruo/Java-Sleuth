# Task List: 移除 KV 解析薄封装（直接使用 KvLineCodec）

- [√] 1. launcher：移除 `HandshakeClient.parseHandshakeKv`，调用点直接使用 `KvLineCodec`
- [√] 2. server：移除 `HandshakeNegotiator.parseHandshakeKv`，调用点直接使用 `KvLineCodec`
- [√] 3. security：移除 `RequestSecurityManager.parseKv`，调用点直接使用 `KvLineCodec`
- [√] 4. 更新相关单测调用点（如有）
- [√] 5. 验证：`mvn test` + `mvn -DskipTests package`
- [√] 6. KB/CHANGELOG 同步（如需要）并迁移方案包到 history
