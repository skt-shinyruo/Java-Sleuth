# Technical Design: KvLineCodec（协议 KV 行 SSOT）

## 设计要点

### 1) 单一实现位置

将通用的行级 KV 解析/编码收敛到 `foundation`（低层模块），避免高层模块相互依赖：

- `foundation/src/main/java/com/javasleuth/command/protocol/KvLineCodec.java`

该位置满足：

- `core` / `launcher` / `agent` 都可以依赖 `foundation`
- 不引入额外第三方依赖（保持 JDK-only 风格）

### 2) API 约定

- `KvLineCodec.parseAfterVerb(line)`
  - 输入：`VERB k=v k=v ...`
  - 行首 verb 会被忽略（从 token[1] 开始解析）
  - key 统一 `toLowerCase()`，保证大小写不敏感
  - 解析规则保持与现有实现一致：按空白 `split("\\s+")`，value 不支持空格

- `KvLineCodec.encode(verb, kv)`
  - 提供最小编码能力（不做转义/引号），用于未来可能的结构化拼装

### 3) 迁移策略

- 保留原有对外方法名以降低改动面：
  - `HandshakeClient.parseHandshakeKv(...)` 改为委托 `KvLineCodec`
  - `HandshakeNegotiator.parseHandshakeKv(...)` 改为委托 `KvLineCodec`
  - `RequestSecurityManager.parseKv(...)` 改为委托 `KvLineCodec`

- 服务端 `SIG` 绑定校验从“复用握手解析器”改为直接使用 `KvLineCodec`：
  - `CommandRequestExecutor` 解析 `SIG sid` 时调用 `KvLineCodec.parseAfterVerb(raw)`

### 4) 版本字段治理

- 握手：继续使用 `HELLO/CONFIG v=1`（由握手模块校验）。
- SIG：维持现有约束（显式拒绝 `v` 字段），避免把握手版本字段语义混入 SIG。
  - 如未来需要 SIG 演进，建议新增独立字段（例如 `sigv=1`），并在安全模块内集中校验。

## 验证

- 单元测试：`mvn test`
- 打包：`mvn -DskipTests package`
