## 背景与问题陈述

本轮目标聚焦两个长期演进风险点：

1) **全局单例/静态状态偏多 → 依赖隐式化**
- `CommandProcessor` 在构造过程中直接调用 `ProductionConfig.getInstance()`、`AuditLogger.getInstance()`、`AuthenticationManager.getInstance()`、`AuthorizationManager.getInstance()`、`RequestSecurityManager.getInstance()` 等，使得依赖关系“只能看代码才能知道”，对测试替换、分层复用与后续拆模块不友好。
- 安全相关管理器（`AuthorizationManager` / `RequestSecurityManager` / `DangerousCommandConfirmationManager`）内部缓存结构缺少统一的 **detach/shutdown** 收敛点，导致“重复 attach → detach → re-attach”时状态可能残留。

2) **线程与生命周期治理未完全收敛**
- `CommandPipeline/CommandExecutionEngine` 内部维护执行线程池，但当前缺少显式 `shutdown()`，无法被 `CommandProcessor` 的关闭编排统一收口。
- 多处仍以 lambda `new Thread(...)` 自建线程工厂，线程命名/daemon/异常处理策略分散（项目虽已有 `SleuthThreadFactory` 与 `SleuthExecutors`，但未完全覆盖）。

## 目标（Success Criteria）

- 依赖显式化：`CommandProcessor` 支持以“composition root 注入”的方式装配依赖，减少构造函数内的 `getInstance()` 链。
- 生命周期闭环：`CommandExecutionEngine` 具备 `shutdown()` 并纳入 `ShutdownCoordinator` 的 graceful/emergency 关闭编排。
- detach 友好：为关键安全/确认管理器补齐 `shutdownInstance()`，在关闭路径中统一清理内部缓存，避免残留。
- 线程治理：将剩余关键线程池 ThreadFactory 改为 `SleuthThreadFactory`，统一命名/daemon/UncaughtExceptionHandler 策略。

