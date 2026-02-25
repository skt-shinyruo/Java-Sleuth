## How

### 1) 拆分 CommandClientHandler（协议分层）

策略：保留现有网络入口与生命周期语义不变，将“协议差异”与“命令执行通用流程”显式化并解耦。

- 新增 text/framed/binary 三类 handler：
  - text：基于 `Utf8LineCodec` 的 legacy 交互（非 framed 输出）。
  - framed：基于 `CMD` / `STREAM` 前缀的 framed 输出（`FrameCodec` 回写）。
  - binary：二进制帧通道（`BinaryFrameCodec` 收发）。
- 抽取共享的命令执行流程（校验 → 解析 → registry → precheck → execute/stream → 审计/监控 → 会话更新）为独立组件，避免三套协议实现复制粘贴。
- 握手协商与参数解析（HELLO/CONFIG/UPGRADE、connId、协议选择）抽取为独立的 handshake 模块，减少主 handler 体积。

验收：`CommandClientHandler.java` 显著降行数；协议行为与现有测试保持一致。

### 2) StackCommand 子模块化

策略：保留 `StackCommand` 作为命令入口（注册点不变），将内部逻辑按职责拆分为：

- 解析：参数解析与选项归一化（count/timeout/depth/bg 等）。
- 会话：active session 生命周期管理（注册/清理/停止）。
- 执行：instrumentation/transformer/enhancer/interceptor 组合与事件拉取循环。
- 格式化：banner、事件行、summary 的输出格式（sink 与非 sink 双路径）。

逐步拆分：优先抽取“Arthas-like stack trace (lite)”链路（最易被改动且最复杂），再将 legacy 采样/分析模块下沉为独立组件。

### 3) TtCommand 子模块化

策略同上，优先抽取 `replay` 模板生成（包含大量字面量转换/转义逻辑），将其沉到独立生成器类，主命令保留 subcommand 分发与 session 管理。

### 4) 验证与文档同步

- 运行 `mvn test`，确保行为契约不变。
- 同步 `helloagents/wiki/modules/command.md`（以及必要时的 modules/launcher.md）与 `helloagents/CHANGELOG.md`。
- 将本次方案包迁移到 `helloagents/history/2026-02/` 并更新 `helloagents/history/index.md`。

