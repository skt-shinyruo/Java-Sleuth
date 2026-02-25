# Change Proposal: 架构边界收敛 + 生命周期治理 + 单例显式化（v2）

## Requirement Background

当前工程已经完成了关键的结构性改造（Launcher/Agent/Core 拆分、两段式 Agent、bootstrap 模块瘦身、ShutdownCoordinator 收口等），但在“可演进边界”“生命周期闭环”“全局单例/静态状态”三个维度仍存在剩余风险点，主要体现在：

1. **边界仍有隐式耦合点**
   - Launcher 侧的协议客户端仍直接依赖 `foundation` 中的安全签名实现（`RequestSecurityManager#getInstance()`），使得协议层/安全层的替换与测试较困难。
   - 协议与握手协商存在 client/server 两端分别实现的情况，虽然复用 `KvLineCodec` 已降低漂移风险，但仍缺少“明确的可复用入口”（例如可注入 signer、可复用 handshake model）。

2. **线程/后台任务治理仍有“遗漏项”**
   - `ProfilerCommand` 自建 `ScheduledExecutorService`，但当前没有被纳入统一 shutdown 编排；在 `stop/detach` 触发时，可能残留定时线程。
   - `vmtool track` 相关的运行态状态（`VmToolSessionRegistry` + `VmToolInterceptor`）在 Agent shutdown 时未被显式清理：会话表与 interceptor 的静态缓存可能跨 detach→re-attach 叠加。

3. **全局单例/静态状态依赖仍偏隐式**
   - 关键路径虽然逐步支持注入与 `shutdownInstance()`，但仍存在“内部类自行拉取单例”的路径（例如 Launcher ProtocolClient 直接拉取 `RequestSecurityManager#getInstance()`），导致：
     - 单测与替换策略更难（难以构造隔离实例、难以 mock）。
     - 多实例/隔离测试或同 JVM 多次 attach/detach 的可预测性下降。

本变更的目标是：在不推翻现有结构的前提下，把剩余的“边界/生命周期/单例显式化”补齐到一个可维护的最小闭环。

## Change Content
1. **协议客户端解耦（Launcher）**：为 `ProtocolClient` 引入可注入的签名/安全管理依赖，默认仍兼容旧行为，但不再强制内部拉取单例。
2. **命令级后台任务纳管（Core）**：引入可选的命令生命周期钩子（优先复用 `AutoCloseable`），让 `CommandRegistry.shutdown()` 能统一关闭带后台线程的命令实现（例如 Profiler）。
3. **vmtool 状态闭环（Core + bootstrap）**：在 `SleuthAgentCore.shutdown()` 中显式 stopAll vmtool sessions 并清理 interceptor 静态缓存，避免 detach→re-attach 残留。
4. **重复实现收敛（Bootstrap/Agent）**：把 `SleuthAgent` 自身 jar 定位逻辑下沉到 `JarLocator` 作为 SSOT，减少重复实现与未来漂移。

## Impact Scope
- **Modules:**
  - `launcher`（ProtocolClient 解耦）
  - `core`（CommandRegistry shutdown 扩展、ProfilerCommand 纳管、Agent shutdown 补齐 vmtool）
  - `bootstrap`（JarLocator 提供通用 CodeSource jar 定位能力）
  - `agent`（SleuthAgent 复用 JarLocator）
- **Files:** 以本次 task.md 列表为准
- **APIs:** 对外 CLI/协议格式不变；仅新增可注入/可关闭的内部扩展点（向后兼容）
- **Data:** 无

## Core Scenarios

### Requirement: detach→re-attach 后不残留 vmtool 运行态
**Module:** core/bootstrap
在 `stop/detach` 或 JVM shutdown 触发 Agent shutdown 后：
- vmtool sessions 表应被清空
- bootstrap interceptor 的 track 缓存应被清空
- 再次 attach 后可重新 track，且不会串到旧 session

#### Scenario: stop 命令触发 SleuthAgentCore.shutdown
- 前置：已执行 `vmtool track` 并产生 trackId
- 执行：触发 shutdown（stop/detach）
- 期望：
  - `VmToolSessionRegistry#listSessions()` 为空
  - `VmToolInterceptor#listTrackStats()` 为空

### Requirement: stop/detach 后不残留 Profiler 定时线程
**Module:** core
Profiler 在运行中触发 shutdown 时应停止采样调度器，避免持续后台采样线程。

#### Scenario: profiler start 后立刻 stop/detach
- 前置：已执行 `profiler start ...`
- 执行：触发 shutdown
- 期望：Profiler 的 `ScheduledExecutorService` 被 shutdown，不再继续 schedule

### Requirement: Launcher 端协议客户端可替换 signer（降低单例隐式依赖）
**Module:** launcher/foundation
ProtocolClient 不应强制内部调用 `RequestSecurityManager#getInstance()`，应允许外部注入，便于 headless client / Web UI / 测试替换。

#### Scenario: 以自定义 RequestSecurityManager 创建 ProtocolClient
- 前置：构造自定义 signer/manager（或未来引入的适配器）
- 执行：使用注入实例发起 `CMD/SIG` 请求
- 期望：行为与默认模式一致，且无需依赖全局单例初始化顺序

## Risk Assessment
- **Risk:** CommandRegistry 调用 `AutoCloseable.close()` 可能影响第三方插件命令（若其 close 有副作用或抛异常）
  - **Mitigation:** best-effort 关闭、异常吞掉并记录 debug；只在 shutdown 路径触发；不改变命令执行语义。
- **Risk:** vmtool stopAll 在 shutdown 中二次 retransform 可能带来额外开销
  - **Mitigation:** best-effort + 忽略异常；stopAll 放在 removeAllEnhancers 之前，减少残留状态；保持整体 shutdown 有界等待策略。

