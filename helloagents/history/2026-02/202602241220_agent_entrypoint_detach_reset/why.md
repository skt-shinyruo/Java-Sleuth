# Change Proposal: Agent 入口 SSOT + detach 清理收敛（避免入口分叉与跨 attach 漂移）

## Requirement Background

当前工程的 agent 启动/关闭链路同时存在于 `core` 与 `container` 两个入口实现中（premain/agentmain/shutdown），并且 bootstrap 侧拦截器（Watch/Trace/Stack/TT/Monitor）包含较多 **static 可变状态**（队列、计数、ThreadLocal、override 配置等）。

该组合在功能扩展后容易出现两个典型问题：

1. **入口逻辑分叉（重复实现）**：core/container 任一入口的修复若未同步到另一端，会导致 attach 与 shutdown 行为逐渐漂移。
2. **同 JVM detach→re-attach 状态漂移**：bootstrap 侧静态状态与 ThreadLocal 的残留会造成跨会话污染（采样/计数/队列数据串线），并提高“ClassLoader 未按预期释放”时的隐性退化风险。

## Architecture Problems（证据点导向）

- **Composition root 不唯一**：`SleuthAgentCore` 与 `SleuthAgentContainerEntrypoint` 都承担了“入口 + 生命周期编排”的职责，且包含重复/相似的启动与清理代码。
- **生命周期清理语义不一致**：
  - shutdown/detach 路径中既存在 “instance shutdown()” 又存在 “singleton shutdownInstance()”，对可重入/可重建的预期不清晰。
  - bootstrap interceptor 的清理更多依赖 unregister/clear 的局部动作，缺少统一的“detach reset”语义（尤其是 ThreadLocal 与 override 状态）。
- **测试隔离不足**：跨 test case 的静态状态清理若不统一，很容易产生顺序依赖与 flaky。

## Change Content

1. **入口 SSOT 收敛**：新增 `SleuthAgentEntrypointSupport`，统一实现 premain/agentmain/shutdown 的公共逻辑；core/container 入口均委托该 SSOT。
2. **shutdown 可重入/可重建兜底**：对仍保持 singleton 语义的 manager，shutdown 统一走 `shutdownInstance()` 保险栓，降低“旧实例残留”导致的 re-attach 退化风险。
3. **bootstrap 静态状态 detach reset**：为关键拦截器增加 `resetForDetach()`，在 detach/shutdown 时集中清理队列/缓存/override 并重置计数；Trace 的 ThreadLocal 采用 epoch 代际惰性清理策略以适配线程池。
4. **测试与知识库同步**：补齐 reset 语义测试，并同步 wiki 文档/变更记录，避免“代码与知识库”漂移。

## Impact Scope

- **Modules:** `core`、`container`、`bootstrap`、`helloagents/wiki`
- **Primary Touch Points（实际落点）**
  - 入口收敛：`core/.../SleuthAgentCore`、`container/.../SleuthAgentContainerEntrypoint`、`core/.../SleuthAgentEntrypointSupport`
  - 生命周期兜底：`core/.../ShutdownCoordinator`
  - detach reset：`bootstrap/.../*Interceptor`、`core/.../AgentGlobalState`、`core test/SleuthTestState`

## Risk Assessment

- **Risk:** detach reset 更“强”，可能改变少量边界诊断行为（例如某些计数/队列在 detach 后不再保留）
  - **Mitigation:** reset 仅在 detach/shutdown 触发；并通过单测锁定 reset 行为，避免无意回归。
- **Risk:** ThreadLocal 清理无法强制遍历所有业务线程
  - **Mitigation:** Trace 使用 epoch 代际策略惰性清理：不依赖全线程遍历，但可在下一次命中时完成清理，兼顾可靠性与可行性。

