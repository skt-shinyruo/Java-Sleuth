# Change Proposal: 配置生命周期对齐（detach 重置 ProductionConfig + monitoring 同步到 bootstrap）

## Requirement Background

项目已支持同 JVM detach→re-attach（入口闩锁/运行时容器/拦截器静态状态 reset）。但配置层仍存在两个跨边界/跨生命周期的不一致点：

1. `ProductionConfig` 为全局单例且初始化时读取 `sleuth.config.file` 等 sysprop，仅构造时加载一次配置文件。
   - detach 后即使 sysprop 被回滚，`ProductionConfig` 仍持有旧 configFile/文件配置快照；
   - re-attach 若使用不同 `configFile`（agentArgs 或 sysprop）会出现配置漂移。
2. bootstrap 拦截器使用 `BootstrapMonitorConfigStore` 作为高频路径配置读取（避免每次读取 sysprop），但 `config set/remove/clear` 写入的是 `ProductionConfig` 的 runtime overrides：
   - runtime overrides 不会自动同步到 bootstrap Store；
   - 导致 `config show/get` 显示与实际拦截器行为不一致（尤其是 trace/monitor 采样与 drop 策略）。

## Goals & Success Criteria

- detach/shutdown 后不遗留上一轮 attach 的 `ProductionConfig` 单例状态；
- re-attach 能重新加载 configFile/sysprop 基线；
- `config set/remove/clear` 对 monitoring 配置的变更能 best-effort 同步到 `BootstrapMonitorConfigStore`，保证实际行为与配置显示一致；
- 保持 bootstrap/foundation 的 JDK-only 约束，不引入第三方依赖。

## Change Content

- `foundation`：为 `ProductionConfig` 增加 detach 重置入口（`resetInstanceForDetach()`）。
- `core`：新增同步桥 `BootstrapMonitoringConfigSync`，从 `ProductionConfig.snapshot()` 解析强类型 monitoring 配置并写入 `BootstrapMonitorConfigStore`。
- 生命周期编排：在 `SleuthAgentEntrypointSupport.shutdown()` finally 中重置 `ProductionConfig`（在 sysprop 回滚之后）。
- 运行时变更：在 `ConfigCommand` 的 set/remove/clear 后调用同步桥，确保 runtime overrides 生效。
- 单测：补齐同步语义测试，并强化 `SleuthTestState` 的全局清理（清 Store + 重置 ProductionConfig）。

## Risk Assessment

- **Risk:** detach 时重置 `ProductionConfig` 可能影响“错误地继续使用旧引用”的代码路径。
  - **Mitigation:** reset 仅在 detach/shutdown 边界触发；旧引用在运行时容器 close 后不应再被使用；测试清理同样收敛到统一入口。

