# Task List: 压缩全局状态蔓延面（v4）

Directory: `helloagents/plan/202602201418_compress_global_state_v4/`

---

## 1. 命令层：去 getInstance（注入优先）
- [√] 1.1 `WatchCommand/TraceCommand/TtCommand/MonitorCommand/StackCommand/JobsCommand`：新增注入 `JobManager`，移除内部 `JobManager.getInstance()` 使用点
- [√] 1.2 `ResetCommand`：注入 `JobManager` + `VmToolSessionRegistry`；interceptor 清理改为统一 bridge 调用
- [√] 1.3 `VmToolCommand`：注入 `VmToolSessionRegistry`，移除字段级单例获取
- [√] 1.4 `HealthCommand/StatusCommand`：注入 `PerformanceOptimizer`，移除内部 `getInstance()`
- [√] 1.5 `AuthCommand/SessionCommand`：去除 `null` 回退到 `AuthenticationManager.getInstance()` 的路径（主路径使用注入）

## 2. 装配层收口
- [√] 2.1 `BuiltinCommandProvider`：统一装配 `JobManager/VmToolSessionRegistry/PerformanceOptimizer`，改为调用新的注入构造函数
- [√] 2.2 `ServerBootstrapper.configureJobManager`：改为接收 `JobManager` 参数，移除内部 `getInstance()`

## 3. 静态清理入口收口（SSOT）
- [√] 3.1 新增 `AgentGlobalState`（或等价命名）：封装 interceptor unregisterAll 的 best-effort 清理
- [√] 3.2 `SleuthAgentRuntime.close`/`SleuthAgentCore.shutdown`/`ResetCommand`：统一走 bridge 清理入口（避免分散与重复）

## 4. 多实例支持（为测试隔离与后续 runtime 组件化铺路）
- [√] 4.1 `JobManager`：构造器改为 `public`（保留 `getInstance()`）
- [√] 4.2 `VmToolSessionRegistry`：构造器改为 `public`，新增 `shutdown/clear`（best-effort）

## 5. 测试与文档同步
- [√] 5.1 更新受影响测试用例（构造签名变化）：本次通过保留 legacy 构造器保持兼容，测试无需额外改动
- [√] 5.2 同步知识库（command/agent 模块说明 + CHANGELOG）
