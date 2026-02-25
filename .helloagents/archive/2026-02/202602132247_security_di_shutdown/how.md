## 方案概述

### 1) 管理器补齐 instance shutdown()

为以下类增加 `shutdown()`（清理内部缓存/状态），并让 `shutdownInstance()` 复用该逻辑：
- `AuthorizationManager`
- `RequestSecurityManager`
- `DangerousCommandConfirmationManager`

这样既能兼容“全局单例模式”，也能支持未来“注入自定义实例”的生命周期收敛。

### 2) 关闭编排优先收口注入实例

在 `ShutdownCoordinator` 中引入可选注入：
- `AuthenticationManager`（已有 shutdown）
- `AuthorizationManager` / `RequestSecurityManager` / `DangerousCommandConfirmationManager`

graceful/emergency 两条路径中：
- 先停止网络/accept/client executor
- 再 shutdown pipeline/executor
- 再 shutdown registry/plugin loader
- 最后 shutdown/clear security managers + audit/metrics

### 3) 命令实现改为注入式依赖

- `VmToolCommand`：构造函数接收 `DangerousCommandConfirmationManager`（用于 subcommand 二次确认），避免内部 getInstance。
- `AuthCommand`/`SessionCommand`：构造函数接收 `AuthenticationManager`，避免内部 getInstance。

并在 `BuiltinCommandProvider`/`CommandRegistry` 的装配链路中把这些依赖注入下去（仍保留默认路径的兼容构造）。

## 风险与兼容性

- 保持旧构造函数/行为：未注入时按原逻辑 fallback 到 `getInstance()`。
- shutdown 顺序保持保守：避免在仍有连接/执行线程时提前清理安全缓存导致误判或 NPE。

