# Change Proposal: 协议/安全 KV 行解析收敛（KvLineCodec）

## 背景与问题

当前协议层存在多处“按空白分割 token + 解析 k=v”的实现：

- 握手解析在 launcher 与 server 各维护一份 `parseHandshakeKv`（重复实现，存在漂移风险）。
- 安全签名 `SIG ...` 在 `RequestSecurityManager` 内维护独立的 `parseKv`（另一套解析入口）。
- 服务端在校验 `SIG sid` 与握手协商 `connId` 的绑定时，复用了握手解析器去解析 `SIG ...`，导致安全逻辑对握手解析器形成不必要耦合。

以上问题的核心不在“当前解析错误”，而在于：协议语法缺少单一实现（SSOT），并且跨模块复用方式不合理，未来协议字段/容错策略演进时容易分叉。

## 目标

- 将“行级 KV（`VERB k=v k=v ...`）解析/编码”下沉到低层公共位置，形成单一实现（SSOT）。
- launcher/server/security 统一复用该实现，消除重复代码。
- 去除服务端对握手解析器解析 `SIG ...` 的复用，避免安全逻辑与握手解析规则耦合。
- 保持现有协议行为与兼容性不变（不改变字段命名/顺序要求，不引入新语法）。

## 非目标

- 不引入 quoted/escaped value（值包含空格）的新语法。
- 不修改握手与 SIG 的业务语义（仅收敛解析入口）。
- 不改变 wire format（HELLO/CONFIG 与 framed/binary 机制不调整）。

## 成功标准

- `mvn test` 通过，`mvn -DskipTests package` 通过。
- 握手 `HELLO/CONFIG` 与安全 `SIG` 的解析规则一致、集中化，避免后续漂移。
- 服务端 `SIG sid` 与 `connId` 绑定校验逻辑不再依赖握手解析器实现。
