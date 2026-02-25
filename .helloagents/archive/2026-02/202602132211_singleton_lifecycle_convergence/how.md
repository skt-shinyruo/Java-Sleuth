## 总体思路

采取“最小可行 + 兼容保守”的改造策略：

1) **把 getInstance() 收敛到 composition root**
- 对外保留原构造方法/静态入口（避免破坏使用方/测试）。
- 在核心入口（`SleuthAgentCore`）中一次性获取单例并注入到 `CommandProcessor` 新构造方法，达到依赖显式化。

2) **补齐 CommandExecutionEngine/CommandPipeline 的 shutdown**
- `CommandExecutionEngine` 新增 `shutdown()`：使用 `SleuthExecutors.shutdownAndAwait(...)` 做有界等待。
- `CommandPipeline` 新增 `shutdown()`：转发到执行引擎。
- `ShutdownCoordinator` 增加对 `CommandPipeline.shutdown()` 的编排调用（在停止接入、等待连接、关闭 clientExecutor 之后执行）。

3) **为安全/确认管理器增加 detach 清理点**
- `AuthorizationManager` / `RequestSecurityManager` / `DangerousCommandConfirmationManager` 增加 `shutdownInstance()`：清空内部缓存并将单例置空。
- 在 `ShutdownCoordinator` 中调用这些 `shutdownInstance()`，与现有的 `AuthenticationManager.shutdownInstance()`、`AuditLogger.shutdown()` 等形成一致的关闭路径。

4) **统一线程工厂**
- 将核心线程池/调度器的 ThreadFactory 统一改用 `SleuthThreadFactory`（daemon + 命名 + UncaughtExceptionHandler）。
- 对“短生命周期线程”（如 restart helper）统一设为 daemon，避免影响目标 JVM 退出语义。

## 风险控制

- 兼容性：保留原 API/构造函数，新增构造函数与 shutdown 方法以“可选能力”方式引入。
- 行为边界：shutdown 顺序保持“先停接入/连接 → 再停执行线程池 → 再清理安全缓存”，降低并发下竞态影响。

