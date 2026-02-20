# why: 压缩全局状态蔓延面（v4）

## 背景与问题

当前 agent 已引入 `SleuthAgentRuntime` 作为运行时容器（收口 attach 生命周期），但在 **命令实现层（command/impl）** 与部分启动组件中，仍存在较多：

- `getInstance()`/静态可变状态的直接调用（例如 `JobManager.getInstance()`、`VmToolSessionRegistry.getInstance()`、`PerformanceOptimizer.getInstance()`）
- 静态注册表清理入口分散（多处直接调用 `*Interceptor.unregisterAll*`）

这些点会带来：

- **可测试性**：单测/集成测更容易串状态，且依赖来源不透明
- **可替换性**：表面支持 DI（构造注入），但命令层仍可绕过注入，回退到单例
- **运行时可靠性**：detach→re-attach 或 reset 场景的清理路径分散，容易遗漏或重复

## 本次目标（不要求一次性重构）

1. **命令层“注入优先”**：将 command/impl 中的 `getInstance()` 调用收口到装配层（registry/provider/factory），命令对象仅依赖构造注入的实例。
2. **静态清理入口收口**：把 `Watch/Trace/Tt/Monitor/Stack` 等 interceptor 的全量清理，收敛到单一 bridge 工具，避免多处散落调用。
3. **多实例铺路**：对 `JobManager`、`VmToolSessionRegistry` 提供可构造实例与显式 `shutdown/clear`，为测试隔离与后续 runtime 组件化提供基础。

