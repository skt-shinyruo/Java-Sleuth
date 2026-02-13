# Task List: 单例收敛 & 生命周期治理补齐

## Tasks
- [√] 1. 为 `CommandExecutionEngine` 增加 `shutdown()`，并由 `CommandPipeline` 统一转发
- [√] 2. `ShutdownCoordinator` 纳入 `CommandPipeline.shutdown()`（graceful/emergency）
- [√] 3. `AuthorizationManager` / `RequestSecurityManager` / `DangerousCommandConfirmationManager` 增加 `shutdownInstance()`，并在关闭路径调用
- [√] 4. `CommandProcessor` 新增“注入式构造方法”，将 `getInstance()` 收敛到 composition root（`SleuthAgentCore`）
- [√] 5. 将剩余关键线程池 ThreadFactory 统一改为 `SleuthThreadFactory`（CommandExecutionEngine / JobManager / MetricsCollector / PerformanceOptimizer / MemoryOptimizer / AuditLogger 等）
- [√] 6. 运行 `mvn test` 验证（含 core + launcher）
- [√] 7. 同步知识库（modules/command.md、modules/security.md、modules/util.md）与 `helloagents/CHANGELOG.md`
- [√] 8. 迁移 solution package 到 `helloagents/history/2026-02/` 并更新 `helloagents/history/index.md`
- [√] 9. git commit
