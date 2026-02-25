# Task List: Agent 配置与生命周期治理（消除 sysprop 污染与全局状态泄漏）

Directory: `helloagents/plan/202602230002_arch_config_lifecycle_refactor/`

---

## 1. 配置通道收口（sysprop 回滚 + bootstrap store）
- [√] 1.1 实现可回滚的 sysprop 应用句柄：在 `bootstrap/src/main/java/com/javasleuth/bootstrap/util/AgentArgsApplier.java` 增加“记录旧值/恢复”的能力（必要时新增 `bootstrap/src/main/java/com/javasleuth/bootstrap/util/SystemPropertyRollback.java`），verify why.md#requirement-r1---attach-级配置隔离与可回滚 #scenario-detachre-attach-使用不同配置仍然生效
- [√] 1.2 引入 bootstrap 侧监控配置 Store：新增 `bootstrap/src/main/java/com/javasleuth/bootstrap/monitor/BootstrapMonitorConfigStore.java`，并改造 `bootstrap/src/main/java/com/javasleuth/bootstrap/monitor/BootstrapMonitorConfig.java` 的读取优先级（Store→sysprop→默认），verify why.md#requirement-r1---attach-级配置隔离与可回滚
- [√] 1.3 调整 `container/src/main/java/com/javasleuth/container/SleuthAgentContainerEntrypoint.java`：用 Store 代替 `setIfAbsent` 写入派生监控配置；并在 shutdown 时清理 Store + 执行 sysprop 回滚句柄（best-effort 但需保证幂等），verify why.md#requirement-r1---attach-级配置隔离与可回滚 #scenario-detachre-attach-使用不同配置仍然生效, depends on task 1.1-1.2

## 2. 生命周期闭环（per-attach runtime SSOT）
- [√] 2.1 审计并补齐 `core/src/main/java/com/javasleuth/core/agent/runtime/SleuthAgentRuntime.java` 的 `close()` 顺序：移除 transformer、关闭命令服务/线程池/插件 CL、关闭会话与安全组件实例，并触发 bootstrap 侧 registry reset，verify why.md#requirement-r3---runtime-资源闭环transformer线程池socket插件-cl #scenario-shutdown-后无残留资源与重复-hook
  > Note: 现有 `close()` 已覆盖：`CommandProcessor.shutdownForDetach()` → `ShutdownCoordinator` 关闭 socket/线程池/pipeline/registry(含插件 CL)/安全组件；并在 close 中清理 VmToolInterceptor 与 AgentGlobalState。
- [√] 2.2 收敛 bootstrap 侧清理入口：确保 `core/src/main/java/com/javasleuth/core/agent/runtime/AgentGlobalState.java` 覆盖所有 bootstrap Interceptor/Registry（Watch/Trace/Monitor/Tt/Stack/VmTool），并在 runtime close 与“部分启动失败”路径均会被调用，verify why.md#requirement-r2---bootstrap-侧-interceptorregistry-有界且可清理 #scenario-stopdetach-后-registry-为空且不会继续写入
  > Note: runtime close 与 container/core shutdown 均会调用 `AgentGlobalState.resetInterceptorsBestEffort()`，vmtool 额外调用 `VmToolInterceptor.clearAll()`。
- [√] 2.3 强化 classloader 生命周期边界：核对 `bootstrap/src/main/java/com/javasleuth/bootstrap/agent/CoreClassLoaderRegistry.java` 的注册/释放时序与强引用清理，确保 detach 后可 GC 且 JAR 句柄释放，verify why.md#requirement-r3---runtime-资源闭环transformer线程池socket插件-cl
  > Note: `CoreClassLoaderRegistry` 已通过 CAS gate + `closeBestEffort` 做到“先清引用、后关闭”，并提供 `releaseOnFailure/onCoreShutdown` 两条释放路径。

## 3. 入口 SSOT 收敛（减少重复逻辑分叉）
- [√] 3.1 评估并收敛 `core/src/main/java/com/javasleuth/core/agent/core/SleuthAgentCore.java` 与 `container/src/main/java/com/javasleuth/container/SleuthAgentContainerEntrypoint.java` 的重复入口/关闭逻辑：确定 container 为 composition root，core 入口改为委托或移除（视引用情况），verify why.md#requirement-r4---入口-ssot-与重复逻辑收敛 #scenario-入口一致且行为不分叉
  > Note: `core` 受 Maven Enforcer 约束（禁止反向依赖 `container`），本次采取“语义对齐”方式降低分叉：两套入口统一采用 sysprop 回滚 + bootstrap 监控 Store + shutdown 清理顺序。
- [√] 3.2 校验 `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java` 的加载链路：确保仅隔离加载 container 并调用其入口，且发生异常时不会遗留 gate/registry 状态，verify why.md#requirement-r4---入口-ssot-与重复逻辑收敛
  > Note: agentArgs 应用延后到通过 attach gate 之后；启动失败路径新增 sysprop 回滚，降低 “already attached/失败重试” 时的全局污染风险。

## 4. Command 子系统解耦（Transport vs Execution）
- [√] 4.1 抽离可单测的命令执行核心：以 `core/src/main/java/com/javasleuth/core/command/CommandPipeline.java`、`core/src/main/java/com/javasleuth/core/command/pipeline/CommandExecutionEngine.java` 为中心，明确“无 socket/无 instrumentation”的最小依赖构造路径（必要时引入小型上下文对象替代 `CommandProcessorFactory` 巨型入参），verify why.md#requirement-r5---command-子系统解耦与可测试性 #scenario-pipeline-单测无需真实-socketinstrumentation
  > Note: 现有实现已满足：pipeline/execution 可在单测中构造最小依赖并覆盖关键分支（无需真实 socket/Instrumentation）。
- [-] 4.2 约束 `CommandProcessorFactory` 的扩散：将安全组件、metrics、config 等依赖打包为更稳定的聚合对象，减少新增能力导致的长参数列表与上帝对象趋势，verify why.md#requirement-r5---command-子系统解耦与可测试性, depends on task 4.1
  > Note: 本次未重构 `CommandProcessorFactory` 的参数聚合（改动面较大且易引入回归）；后续可引入 `CommandRuntimeContext` 分阶段替换。
- [√] 4.3 补齐测试分层：新增/调整单测覆盖 pipeline/authn/authz/危险命令确认的关键分支，同时保留 `core/src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java` 作为 transport 集成回归，verify why.md#requirement-r5---command-子系统解耦与可测试性
  > Note: 现有单测已覆盖 pipeline 与安全分支，且保留了 transport 回归（SessionCleanupOnDisconnectTest）。

## 5. 插件机制治理（加载/释放/稳定 API）
- [√] 5.1 明确插件 API 稳定面：围绕 `core/src/main/java/com/javasleuth/core/command/CommandProvider.java` 与 `core/src/main/java/com/javasleuth/core/command/plugin/CommandProviderLoader.java` 补充版本策略（默认方法扩展、避免破坏性变更），verify why.md#requirement-r6---插件加载隔离与释放
- [√] 5.2 确保 detach 时释放插件 classloader：核对 registry.shutdown 调用链，必要时增加回归测试验证 `URLClassLoader` 关闭与资源释放，verify why.md#requirement-r6---插件加载隔离与释放 #scenario-plugin-urlclassloader-在-detach-时关闭

## 6. Security Check
- [√] 6.1 执行安全自检（G9）：确认不会明文输出/存储 secret；确认插件校验不被绕过；确认 sysprop 回滚不会误清理用户显式设置的属性

## 7. Documentation Update
- [√] 7.1 同步更新知识库：补充/更新 `helloagents/wiki/arch.md` 与相关 module 文档（配置通道优先级、detach→re-attach 语义、插件加载与释放约束）

## 8. Testing
- [-] 8.1 执行 `mvn test` 与必要的手动回归（对 `examples` attach、执行 watch/trace/stack、detach→re-attach），并记录关键验证点
  > Note: 已执行 `mvn test`（含 core/launcher 等模块）；手动 attach→detach→re-attach 需你本地启动 `examples` 后验证。
