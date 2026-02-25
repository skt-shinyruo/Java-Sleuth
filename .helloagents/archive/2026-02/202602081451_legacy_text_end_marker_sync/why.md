# Change Proposal: legacy 文本协议响应边界稳定化（sync END marker）

## Requirement Background
当前 legacy 文本协议的回包方式是“按行写出 + `\n` 分隔”。对于非流式（sync）命令，协议层没有显式 end frame；客户端通常只能通过“读到一行就认为结束”或“短超时猜测结束”的方式判断边界。

与此同时，输出净化（`InputValidator.sanitizeOutput`）并不会移除 `\r/\n`，且在触发截断时会拼接带换行的截断提示，这会让原本预期“一行回包”的 sync 响应变成多行。对于只读一行/以超时猜测结束的 legacy 客户端，剩余行可能被误当成下一次命令的回包，从而出现粘包式错乱与协议边界错位。

因此需要在**协议层**提供确定的响应边界，使 legacy 客户端不再依赖超时猜测，并允许输出包含换行（例如多行堆栈、表格、截断提示等）而不破坏读写对齐。

## Change Content
1. **服务端（Agent）：** 在 legacy text（非 framed/binary）模式下，对 **sync 命令** 的响应（成功/失败/异常兜底）在末尾追加单行 `END` marker（受配置开关控制），作为本次响应的确定边界。
2. **客户端（Launcher/第三方）：** legacy 模式读响应以 `END` 为准结束读取；对未发送 `END` 的旧 Agent 保留超时 fallback（兼容旧版本）。
3. **文档与默认配置：** 明确 `protocol.text.end.marker.enabled` 的语义覆盖 legacy sync（不止 streaming），并声明 `END` 为协议保留终止行（legacy 响应流内不可作为普通输出行出现）。

## Impact Scope
- **Modules:** command/protocol、launcher、security、config
- **Files:**
  - `core/src/main/java/com/javasleuth/command/server/protocol/CommandRequestExecutor.java`
  - `core/src/main/java/com/javasleuth/command/server/protocol/TextReplyChannel.java`
  - `core/src/main/java/com/javasleuth/security/InputValidator.java`
  - `core/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`
  - `core/src/main/resources/sleuth-default.properties`
- **APIs:** N/A
- **Data:** N/A

## Core Scenarios

### Requirement: Legacy Text Response Boundary (REQ-LEGACY-END)
**Module:** command/protocol  
在 legacy text 模式下，为每个命令响应提供确定边界，避免依赖超时猜测结束。

#### Scenario: Sync Output Contains Newlines (SCN-LEGACY-SYNC-MULTILINE)
前置：客户端处于 legacy text 模式，且服务端开启 end marker  
- 服务端允许输出包含 `\r/\n`（多行输出/截断提示/表格等）
- 客户端读取直到遇到单行 `END` 才结束本次响应
- 不会将残留行串入下一次命令的响应

#### Scenario: Sync Error Also Has Deterministic End (SCN-LEGACY-SYNC-ERROR-END)
前置：sync 命令执行失败（precheck/execute/兜底异常）  
- 错误消息发送后同样追加 `END` marker
- 客户端无需区分成功/失败路径，均可按 `END` 结束读取

#### Scenario: Backward Compatibility With Older Agents (SCN-LEGACY-COMPAT-OLD)
前置：旧 Agent 版本不发送 `END` marker  
- 客户端保留短超时 fallback（兼容旧行为）
- 在多行/延迟输出场景仍可能存在边界不确定性（已知限制）

## Risk Assessment
- **Risk:** 旧 legacy 客户端若只读一行，会因新增 `END` 残留而造成后续命令错位（兼容性风险）
  - **Mitigation:** 提供配置开关；在握手/文档中明确行为；Launcher 保留对旧 Agent 的 fallback 但对新 Agent 优先 `END`
- **Risk:** 若输出中出现单独一行 `END`，可能与终止行冲突（歧义风险）
  - **Mitigation:** 将 `END` 声明为协议保留终止行；必要时对输出中“整行 END”做转义/替换（作为可选增强）

