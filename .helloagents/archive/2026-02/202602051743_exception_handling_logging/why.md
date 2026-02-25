# Change Proposal: 异常处理与输出/日志策略统一（参考 Arthas 的通道分层）

## Requirement Background

当前代码在 `src/main/java` 中存在明显的异常处理与输出/日志策略不统一问题：

- `catch (Exception|Throwable)` 出现频繁（201 次，覆盖 64 个文件），且大量 catch 块缺少可追溯的诊断信号，容易造成“错误被吞/语义被弱化”。
- `throws Exception` 仍较多（76 次，覆盖 50 个文件），导致上层不得不继续使用广义捕获或继续上抛，形成不良循环。
- SpotBugs 已标出 `REC_CATCH_EXCEPTION`（例如 `CommandRegistry.java`、`SleuthLauncher.java`、`ReflectionUtils.java`），提示当前广义捕获在这些关键边界点上存在风险。

风险集中体现在：

1. **可观测性与排障成本**：异常根因与调用链上下文不稳定，出现线上问题时难以从用户输出定位到诊断日志。
2. **输出通道混杂**：用户输出（命令结果/协议返回）与系统诊断信息（堆栈、内部告警）边界不清晰，容易污染 stdout/协议输出并影响调用方解析。

参考 Arthas 的实践，本次治理以“**通道分离 + 边界兜底 + 对外语义化返回**”为核心：用户侧输出保持稳定、可读、可解析；诊断堆栈与上下文统一进入内部日志通道，支持后续关联与追踪。

## Change Content

1. **异常处理分层**：
   - 边界层（launcher / command pipeline / 协议入口）允许兜底捕获，但必须执行“诊断日志记录 + 用户可读错误返回”的双通道策略。
   - 内部层（util / vmtool / monitor / 具体命令实现）优先捕获更具体异常或直接传播，避免 `catch(Exception)` 作为常规控制流。
2. **统一错误语义（用户可读）**：
   - 用户侧只返回短消息（不含堆栈），必要时附带可关联的错误标识（如 errorId/会话信息）。
   - 诊断侧记录完整堆栈与上下文（commandName/args/session/conn 等）。
3. **统一诊断日志通道**：
   - 系统诊断信息统一使用 `SleuthLogger`（stderr）输出，禁止 `printStackTrace()` 与直接 `System.err.println` 作为诊断手段。
   - 审计/安全日志（`AuditLogger`）维持独立落盘通道，是否镜像到控制台由配置控制，避免污染目标 JVM 的 stdout/stderr。
4. **优先修复 SpotBugs 热点与高风险点**：
   - 首先处理 `CommandRegistry` / `SleuthLauncher` / `ReflectionUtils` 的广义捕获与静默吞错模式，作为阶段性验收点。

## Impact Scope

- **Modules:** `launcher` / `command` / `command/server/protocol` / `util` / `security`
- **Files (priority):**
  - `src/main/java/com/javasleuth/command/CommandPipeline.java`
  - `src/main/java/com/javasleuth/command/CommandRegistry.java`
  - `src/main/java/com/javasleuth/command/server/protocol/CommandRequestExecutor.java`
  - `src/main/java/com/javasleuth/command/CommandProcessor.java`
  - `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`
  - `src/main/java/com/javasleuth/util/ReflectionUtils.java`
  - `src/main/java/com/javasleuth/util/SleuthLogger.java`
  - `src/main/java/com/javasleuth/security/AuditLogger.java`
- **APIs:** 命令输出/错误输出格式可能更稳定（更少堆栈噪声），失败信息更一致；不计划引入新的对外公共 API
- **Data:** 无新增持久化数据结构；审计/安全日志文件仍为现有路径与格式

## Core Scenarios

### Requirement: 命令执行失败的稳定错误输出
**Module:** command / protocol
命令执行出现运行时异常或受检异常时，用户侧应获得稳定的错误信息，而不是堆栈/内部细节。

#### Scenario: 命令实现抛出异常（RuntimeException/ReflectiveOperationException）
前置：用户发起命令执行（CLI 或协议）
- 返回：短消息（可读、可解析），不包含堆栈
- 记录：`SleuthLogger.error(..., t)` 打印完整堆栈与上下文

### Requirement: 广义捕获收敛与语义不丢失
**Module:** launcher / util
SpotBugs 标出的 `REC_CATCH_EXCEPTION` 热点应优先整改，避免静默吞错与语义弱化。

#### Scenario: 反射失败或初始化失败
- 内部层尽量抛出具体异常（保留 cause）
- 边界层统一转换为用户错误 + 诊断日志

### Requirement: 输出通道不混杂
**Module:** util / security
系统日志（诊断）与用户输出（命令结果/协议输出）通道必须清晰。

#### Scenario: 审计/安全日志输出
- 审计/安全事件写入落盘日志为 SSOT
- 控制台镜像严格受配置控制，且不使用裸 `System.err.println`

## Risk Assessment

- **Risk:** 行为变化（错误输出文案/格式变更、部分命令在异常时的返回可能更“克制”）
  - **Mitigation:** 分批改造（先边界层与 SpotBugs 热点），保持向后兼容；为用户输出保留既有关键字段/前缀
- **Risk:** 日志噪声增加（统一记录堆栈后 ERROR/WARN 可能增多）
  - **Mitigation:** 遵循 `logging.level`/`logging.console.enabled` 约束；必要时对重复异常增加限频策略
- **Risk:** 敏感信息泄露（异常 message/args 包含 token/密码等）
  - **Mitigation:** 用户输出与诊断日志均执行最小披露；对已知敏感字段进行脱敏；安全相关事件继续走 `AuditLogger` 规范通道

