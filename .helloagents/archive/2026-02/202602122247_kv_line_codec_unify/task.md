# Task List: KV 行解析收敛（握手/安全）

- [√] 1. 在 `foundation` 新增 `KvLineCodec`，作为 `VERB k=v ...` 的单一解析/编码实现
- [√] 2. launcher 握手解析：`HandshakeClient.parseHandshakeKv` 委托 `KvLineCodec`
- [√] 3. server 握手解析：`HandshakeNegotiator.parseHandshakeKv` 委托 `KvLineCodec`
- [√] 4. 安全 SIG 解析：`RequestSecurityManager.parseKv` 委托 `KvLineCodec`
- [√] 5. 去耦合：`CommandRequestExecutor` 不再复用握手解析器解析 `SIG ...`
- [√] 6. 验证与同步：`mvn test` + `mvn -DskipTests package`；更新 KB/CHANGELOG
