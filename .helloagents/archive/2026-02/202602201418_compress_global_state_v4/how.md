# how: 方案设计（v4）

## 总体策略

- **收口点选择**：把“默认单例获取”放在装配层（`BuiltinCommandProvider`/`CommandProcessorFactory` 等），命令实现只认构造参数。
- **兼容性**：保留必要的兼容构造器/工厂方法，但将其标记为 legacy/bridge-only，主路径全部改为显式注入。
- **清理 SSOT**：提供 `AgentGlobalState`（或等价命名）作为静态注册表清理的单一入口，被 runtime.close 与 reset 命令复用。

## 关键改造点

1. **Command 实现层**
   - `WatchCommand/TraceCommand/TtCommand/MonitorCommand/StackCommand/JobsCommand`：
     - 新增注入 `JobManager` 的构造函数
     - 移除执行路径内的 `JobManager.getInstance()` 直接调用
   - `ResetCommand`：
     - 注入 `JobManager` 与 `VmToolSessionRegistry`
     - interceptor 清理改为调用 `AgentGlobalState.resetInterceptorsBestEffort()`
   - `VmToolCommand`：
     - 注入 `VmToolSessionRegistry`
     - 移除字段级 `VmToolSessionRegistry.getInstance()`（避免构造时隐式取单例）
   - `HealthCommand/StatusCommand`：
     - 注入 `PerformanceOptimizer`
     - 将 `getInstance()` 下沉到 provider 装配处

2. **装配层（provider/registry/factory）**
   - `BuiltinCommandProvider`：
     - 统一创建/获取默认 `JobManager`、`VmToolSessionRegistry`、`PerformanceOptimizer`
     - 用注入构造函数创建命令对象
   - `ServerBootstrapper`：
     - `configureJobManager` 改为接收 `JobManager` 参数，移除内部 `getInstance()`

3. **多实例支持**
   - `JobManager`：构造器开放为 `public`（保留单例 `getInstance()` 兼容）
   - `VmToolSessionRegistry`：构造器开放为 `public`，补充 `shutdown/clear`（best-effort）

## 风险与回滚

- 改动集中在命令装配与命令实现的构造签名；通过保持兼容构造器/默认路径可降低升级风险。
- 若出现兼容问题，可临时回退到旧构造器（但主路径继续使用注入）。

