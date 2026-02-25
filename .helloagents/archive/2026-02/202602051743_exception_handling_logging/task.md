# Task List: 异常处理与输出/日志策略统一

Directory: `helloagents/plan/202602051743_exception_handling_logging/`

---

## 1. Command 边界异常兜底与用户错误规范化

- [√] 1.1 新增统一错误映射器 `src/main/java/com/javasleuth/command/CommandErrorMapper.java`：将 `Throwable` 映射为用户短错误（不含堆栈），并支持最小披露/脱敏策略
- [√] 1.2 调整 `src/main/java/com/javasleuth/command/CommandPipeline.java`：在统一捕获点调用 `SleuthLogger.error(msg, t)` 输出诊断堆栈；`Result.error` 仅使用 `CommandErrorMapper` 产物
- [√] 1.3 调整 `src/main/java/com/javasleuth/command/server/protocol/CommandRequestExecutor.java`：失败响应不混入堆栈；必要时附带可关联信息（session/conn/errorId）
- [√] 1.4 调整 `src/main/java/com/javasleuth/command/CommandProcessor.java`：CLI/交互输出保持“结果输出 vs 诊断日志”边界（用户输出不打印堆栈）（现状已满足，无需额外改动）

## 2. SpotBugs 热点优先整改（REC_CATCH_EXCEPTION）

- [√] 2.1 调整 `src/main/java/com/javasleuth/command/CommandRegistry.java`：将 `catch(Exception)` 收敛为具体异常（反射/注册相关），并保留 cause；必要时记录诊断日志
- [√] 2.2 调整 `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`：移除 `System.err.println`/`System.out.println`/`printStackTrace()` 的诊断用法，统一走 `SleuthLogger`；主入口兜底捕获不吞 fatal errors
- [√] 2.3 调整 `src/main/java/com/javasleuth/util/ReflectionUtils.java`：替换 `catch(Exception|Throwable ignored)` 的静默吞错为“显式忽略（DEBUG）或抛出（保留 cause）”策略，并补充必要的上下文日志

## 3. 输出/日志通道收敛（禁止裸控制台诊断）

- [√] 3.1 调整 `src/main/java/com/javasleuth/util/SleuthLogger.java`：消除 `printStackTrace()` 分支，统一使用可控格式输出 throwable
- [√] 3.2 调整 `src/main/java/com/javasleuth/security/AuditLogger.java`：审计/安全日志以落盘为 SSOT；控制台镜像严格受配置控制，且不使用裸 `System.err`

## 4. Security Check

- [√] 4.1 执行安全检查：确保用户错误输出不包含敏感字段（password/secret/session/token），诊断日志与审计日志遵循脱敏策略（已通过 mapper 脱敏 + 单测覆盖）

## 5. Documentation Update

- [√] 5.1 更新知识库：补充“异常分层 + 通道分离 + catch(Throwable) 约束 + 用户错误最小披露”规范（建议落点：`helloagents/wiki/modules/util.md` 与 `helloagents/wiki/modules/command.md`）

## 6. Testing

- [√] 6.1 更新/新增命令异常场景测试（优先复用现有测试基座）：`src/test/java/com/javasleuth/command/CommandProcessorTest.java` 或新增 `src/test/java/com/javasleuth/command/CommandErrorHandlingTest.java`
- [√] 6.2 更新 `src/test/java/com/javasleuth/security/AuditLoggerTest.java`：覆盖控制台镜像开关与输出通道约束
- [√] 6.3 运行验证：`mvn test`（已通过）；SpotBugs 未在 pom 中集成，本次不执行
