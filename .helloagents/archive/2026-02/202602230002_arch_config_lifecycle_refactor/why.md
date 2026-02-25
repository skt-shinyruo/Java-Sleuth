# Change Proposal: Agent 配置与生命周期治理（消除 sysprop 污染与全局状态泄漏）

## Requirement Background

本项目采用“两段式 Agent（bootstrap + isolated core/container）”以降低目标 JVM 中的依赖碰撞风险，但在实现层面仍存在一些典型的架构隐患，会影响可维护性、可测试性与 detach→re-attach 的确定性：

- **配置通道过度依赖 `System properties`**：`AgentArgsApplier` 会把 agentArgs 写入 sysprop；`ProductionConfig` 与 bootstrap 侧拦截器配置读取也会动态读取 sysprop。这在目标 JVM 内属于全局共享状态，容易造成跨 attach 的配置漂移与残留。
- **全局静态注册表与 best-effort 清理并存**：bootstrap 侧 Interceptor/Registry（例如 `StackInterceptor` 的队列注册表、`CoreClassLoaderRegistry` 的隔离 ClassLoader 注册）依赖静态结构维持运行态；虽有清理逻辑，但大量 best-effort + 吞异常会降低可观测性与问题定位效率。
- **入口/装配存在重复实现**：同时存在 `agent`→`container` 路径与 core 内的装配/关闭逻辑（例如 `SleuthAgentCore` 与 `SleuthAgentContainerEntrypoint` 的相似 shutdown 逻辑）。长期看会导致修复分叉与行为漂移。
- **命令子系统装配过重、边界偏粗**：`CommandProcessorFactory` 负责拼装大量对象，`CommandProcessor` 与 socket accept loop/线程池/认证鉴权/命令执行链路耦合，单元测试容易被迫走集成化路径。
- **插件机制在隔离 classloader 下可用但需要更强“释放与稳定性”约束**：当前插件使用 `ServiceLoader` 与插件目录 `URLClassLoader`，需要确保 detach 时关闭 classloader、并限制插件 API 变化对兼容性的冲击。

本变更希望以“**attach-scoped 状态为 SSOT**”为原则，把跨 attach 的隐性全局状态收敛、可回滚，并把命令与插件机制做出可持续演进的边界。

## Change Content

1. **配置通道收口**：引入可回滚的 sysprop 应用策略；bootstrap 侧监控配置从“派生值写入 sysprop”迁移为“写入 bootstrap 内部轻量配置存储（JDK-only）”，sysprop 仅作为外部 override/兼容通道。
2. **生命周期闭环强化**：以 `SleuthAgentRuntime.close()` 为唯一资源收口点，确保 transformer、socket、线程池、会话、插件 classloader、bootstrap registry 都在 detach/shutdown 路径可幂等清理。
3. **入口 SSOT 收敛**：明确 `container` 为 composition root，减少/消除 core 侧重复入口逻辑，避免长期演进中出现行为分叉。
4. **Command 子系统解耦**：把“协议/IO/并发模型”与“命令执行与授权逻辑”拆分为更可测试的边界，降低 `CommandProcessorFactory` 上帝对象倾向。
5. **插件扩展机制治理**：完善插件加载与释放约束（allowlist/sha256/关闭 classloader），并明确/隔离插件 API 的稳定范围与版本策略。
6. **架构守护增强**：在已移除 ArchUnit 的前提下，继续强化 Maven module/enforcer 规则与最小化架构校验，防止边界回流与 split package 回归。

## Impact Scope

- **Modules:**
  - `bootstrap`
  - `foundation`
  - `agent`
  - `container`
  - `core`
  - `launcher`
  - `examples`（仅用于验证场景，可选）
- **Files:** 以配置与生命周期相关类、命令子系统与插件加载器为主（详见 task.md）。
- **APIs:** 对外命令协议/命令名称保持不变；主要变更为内部装配与配置读取/清理语义。
- **Data:** 无持久化数据结构变更。

## Core Scenarios

### Requirement: R1 - Attach 级配置隔离与可回滚
**Module:** bootstrap / container / foundation

收口 agentArgs 与 bootstrap 监控配置的传播方式，确保：
- 本次 attach 写入的全局状态（若有）可被恢复；
- re-attach 使用不同配置时能够生效；
- sysprop 作为“外部 override”保留兼容，但不再作为内部派生配置的 SSOT。

#### Scenario: detach→re-attach 使用不同配置仍然生效
前置：同 JVM 内 attach→shutdown→再次 attach
- 第二次 attach 的监控参数能够生效（不被第一次 sysprop 残留阻挡）
- shutdown 后 sysprop 不残留由 agent 内部派生写入的配置

### Requirement: R2 - Bootstrap 侧 Interceptor/Registry 有界且可清理
**Module:** bootstrap

所有 bootstrap 可见的 Interceptor/Registry：
- 必须提供幂等 `unregisterAll/clearAll`；
- 不允许无界增长的 map/queue（必要时增加上限、驱逐策略与统计）。

#### Scenario: stop/detach 后 registry 为空且不会继续写入
前置：开启 watch/trace/stack/tt/vmtool 等任意组合
- stop/detach 后对应 registry 为空
- 业务线程不再继续向旧队列发布事件（避免强引用与 OOM 风险）

### Requirement: R3 - runtime 资源闭环（transformer/线程池/socket/插件 CL）
**Module:** core / container

`SleuthAgentRuntime.close()` 必须保证：
- 移除/停用已注册的 transformer
- 关闭 server socket 与 client executor
- 关闭插件 classloader（若启用）
- 收口会话与安全组件实例（shutdown）
- 触发 bootstrap 侧全局状态清理（best-effort，但需可观测）

#### Scenario: shutdown 后无残留资源与重复 hook
前置：命令服务已启动、至少一个客户端连接/执行过命令
- shutdown 后不残留 server 监听端口
- shutdown hook 不会随 re-attach 累积
- 线程池/后台线程能在超时内终止

### Requirement: R4 - 入口 SSOT 与重复逻辑收敛
**Module:** agent / container / core

避免存在多套“入口与生命周期编排”导致行为分叉，明确：
- `agent` 只负责隔离加载并调用 `container` 入口
- `container` 是 composition root
- core 保持“可被装配的库”，尽量不再承载重复入口

#### Scenario: 入口一致且行为不分叉
前置：premain 与 agentmain 两种入口
- 进入同一条启动与关闭链路
- shutdown/clear 的顺序一致

### Requirement: R5 - Command 子系统解耦与可测试性
**Module:** core

命令处理应当具备“无 socket/无 attach”的可测核心：
- pipeline/registry/authn/authz 可通过纯单测覆盖关键分支
- socket accept loop 作为独立 transport 层被集成测试覆盖

#### Scenario: pipeline 单测无需真实 socket/instrumentation
- 可在单测中构造最小依赖（stub ConfigView / MetricsCollector 等）执行命令链路
- 不需要真实 `ServerSocket` 与 `Instrumentation`

### Requirement: R6 - 插件加载隔离与释放
**Module:** core

插件加载与卸载必须确定性：
- allowlist + sha256 校验失败必须拒绝加载并审计记录
- detach/shutdown 必须关闭插件 `URLClassLoader`
- 明确插件 API 的稳定范围与兼容策略（最小化破坏性改动）

#### Scenario: plugin URLClassLoader 在 detach 时关闭
前置：plugins.enabled=true 且加载到至少一个插件
- shutdown 时 `URLClassLoader` 关闭，JAR 句柄释放（Windows 场景重要）

## Risk Assessment

- **Risk:** sysprop 语义变更导致旧脚本/文档依赖的行为变化  
  **Mitigation:** 保留 sysprop override 兼容；新增“回滚/恢复”策略；通过示例与文档明确优先级。
- **Risk:** attach/detach 的时序边界更复杂（异常路径、部分启动失败）  
  **Mitigation:** 以 runtime.close 为 SSOT，所有路径最终进入同一清理链路；增加审计/日志与测试覆盖异常分支。
- **Risk:** 命令子系统重构引入回归  
  **Mitigation:** 分阶段拆分（先抽象边界再搬迁实现）；保留集成测试覆盖现有协议行为。 
