# Technical Design: 异常处理与输出/日志策略统一（分层兜底 + 通道分离）

## Technical Solution

### Core Technologies

- Java（Maven 项目）
- `SleuthLogger`：系统诊断日志统一通道（stderr，可配置等级）
- `SleuthLogContext`：连接/会话/命令上下文（用于日志关联）
- command pipeline：`CommandPipeline.Result(success/output/error)` 作为对外执行结果载体
- `AuditLogger`：审计/安全日志落盘通道（独立于系统诊断日志）

### Implementation Key Points

1. **边界层集中处理异常**
   - 在命令执行边界（`CommandPipeline`/协议 executor）集中捕获异常，避免在每个命令实现中重复 `catch(Exception)`。
   - 捕获后执行两件事：
     1) `SleuthLogger.error(带上下文的诊断信息, throwable)` 记录完整堆栈；
     2) 通过统一 mapper 生成用户可读错误字符串（不含堆栈），写入 `Result.error` 或协议错误字段。
2. **用户错误信息的规范化与脱敏**
   - 新增轻量 mapper（例如 `CommandErrorMapper`），把 Throwable 映射为：
     - 用户短消息：可读/可解析/最小披露
     - （可选）错误分类或 errorId：用于与诊断日志关联
   - 对 message/args 中可能的敏感内容执行脱敏或不回显策略（优先不回显）。
3. **收敛广义捕获（SpotBugs 热点优先）**
   - 对 `REC_CATCH_EXCEPTION` 热点：
     - 能精确捕获的改为捕获具体异常（例如反射相关异常、参数校验异常）。
     - 不能避免的边界兜底捕获，需要确保不吞异常语义：诊断日志记录 + 对外短错误。
   - 对 `catch(Throwable)`：
     - 仅保留在确需保护插桩/拦截链路的地方；
     - 显式透传致命错误（`Error`/`VirtualMachineError` 等），避免掩盖 JVM 失稳。
4. **输出通道治理**
   - 禁止 `printStackTrace()`；统一改为 `SleuthLogger.*(msg, t)`。
   - 禁止用 `System.err.println` 打诊断；统一走 `SleuthLogger`（stderr）或 `AuditLogger`（落盘）；
   - `System.out` 的使用需要明确归类：仅用于用户输出（命令结果/示例程序），不得混入诊断堆栈。

## Architecture Decision ADR

### ADR-1: 用户输出与诊断日志分通道
**Context:** 当前存在用户输出与诊断信息混杂的风险，异常发生时可能污染 stdout/协议输出并降低可解析性。  
**Decision:** 用户输出（命令结果/协议响应）只承载稳定结果与短错误；诊断信息（堆栈、上下文）统一进入 `SleuthLogger`（stderr）/审计落盘日志。  
**Rationale:** 与 Arthas 的实践一致：用户侧稳定、内部侧可观测；排障时通过内部日志与上下文字段定位。  
**Alternatives:** 直接把堆栈回显给用户 → 拒绝原因：污染输出、信息泄露风险高、且难以自动化处理。  
**Impact:** 错误输出更一致；需要为排障提供“如何查诊断日志”的指引与关联信息（如 session/conn/errorId）。

### ADR-2: 广义捕获边界化 + Fatal Error 透传
**Context:** `catch(Exception|Throwable)` 分布广且语义不一，容易把真正的系统错误变成普通失败分支。  
**Decision:** `catch(Exception)` 只允许在边界层兜底（并记录诊断日志）；内部层优先捕获具体异常或传播；`catch(Throwable)` 限制在插桩/拦截保护点且透传致命错误。  
**Rationale:** 减少吞错与语义弱化，降低 SpotBugs 告警与维护成本。  
**Alternatives:** 全局保留 `catch(Exception)` 但补日志 → 拒绝原因：SpotBugs 与代码语义仍不清晰，且容易演化为“继续 catch”模式。  
**Impact:** 需要分批梳理并增加少量单测覆盖，避免行为回归。

## Security and Performance

- **Security:**
  - 用户错误信息最小披露：不回显堆栈、不回显潜在敏感参数（token/password/session 等）
  - 诊断日志同样避免泄露敏感字段；安全相关事件保持走 `AuditLogger` 的脱敏与独立通道
- **Performance:**
  - ERROR 堆栈输出只在异常路径发生；默认日志等级可通过 `logging.level` 控制
  - 对高频失败点（如重复参数错误/无权限）优先输出短错误，不刷屏堆栈；必要时引入限频

## Testing and Deployment

- **Testing:**
  - 增补/更新命令执行失败场景的测试：确保 `Result.error` 不包含堆栈、且仍可定位到诊断日志
  - 更新 `AuditLogger` 相关测试：确保控制台镜像受配置控制且不使用裸 `System.err`
- **Deployment:**
  - 不引入新依赖；主要变更为逻辑收敛与输出通道调整
  - 在 CI 中加入 SpotBugs（若已集成）或至少本地运行验证，作为阶段性验收指标

