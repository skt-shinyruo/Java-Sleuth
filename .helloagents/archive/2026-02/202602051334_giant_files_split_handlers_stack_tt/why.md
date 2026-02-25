## Why

当前命令子系统仍存在明显的“巨型文件”风险，主要集中在：

- `src/main/java/com/javasleuth/command/server/CommandClientHandler.java`：单文件同时承担握手协商、协议分流（legacy/framed/binary）、会话与安全校验、命令调度、流式回写与错误收敛等职责。
- `src/main/java/com/javasleuth/command/impl/StackCommand.java`、`src/main/java/com/javasleuth/command/impl/TtCommand.java`：解析/会话/执行/格式化等逻辑混杂，后续改动容易引入回归，单测也难以精准覆盖。

这些耦合会带来：

1. 修改成本高：任何小改动都需要理解大量无关逻辑。
2. 风险扩散：协议与安全边界一旦被误改，回归影响面大。
3. 可测性差：难以对单一职责做隔离单测（尤其是协议/回写与命令执行交织时）。

本次目标：

- 将 `CommandClientHandler` 按协议职责拆分为 text/framed/binary handlers，并抽取共享执行/回写抽象，降低单文件复杂度。
- 对 `StackCommand` / `TtCommand` 启动子模块化（解析/会话/执行/格式化拆分），使核心逻辑可独立单测与复用。
- 保持对外协议与行为兼容：HELLO/CONFIG、CMD/STREAM、binary upgrade、鉴权/审计/监控语义不变；全量测试保持通过。

