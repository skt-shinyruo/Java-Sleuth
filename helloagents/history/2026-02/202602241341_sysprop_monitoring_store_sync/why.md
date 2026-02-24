# Change Proposal: `sysprop set sleuth.monitoring.*` 同步 BootstrapMonitorConfigStore

## Requirement Background

bootstrap 拦截器的高频路径优先读取 `BootstrapMonitorConfigStore`（避免每次 `System.getProperty` 的同步开销），而 core 会在 attach 与 `config set/remove/clear/reload` 后同步 effective monitoring 配置到该 Store。

但如果用户在运行中使用 `sysprop set sleuth.monitoring.*` 修改 monitoring 相关 sysprop：

- System property 已更新
- 但 Store 仍持有旧值并优先级更高

会导致“用户看到 sysprop 已更新，但拦截器实际行为未变化”的配置漂移问题。

## Goals & Success Criteria

- `sysprop set sleuth.monitoring.*` 成功后 best-effort 触发与 `config` 命令一致的同步逻辑（`ProductionConfig.snapshot()` -> typed config -> `BootstrapMonitorConfigStore`）
- 保持拦截器热路径仍以 Store 读取为主（不倒退为每次读 sysprop）
- 新增单测锁定语义，避免后续回归

## Risk Assessment

- **Risk:** `sysprop set` 增加一次同步动作（解析 typed config）
  - **Mitigation:** 仅在手工执行 `sysprop set` 成功后触发；非热路径，且 best-effort 不影响命令可用性

