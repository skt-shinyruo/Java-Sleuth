# Change Proposal: 落地 `config reload`（运行时重新加载配置文件）

## Requirement Background

当前 `config reload` 在命令层属于 stub（提示需要重启），而配置加载由 `ProductionConfig` 单例在构造时一次性完成。

在以下场景中会引发可运维性与一致性问题：

- 运维修改 `sleuth.properties`（或切换 `-Dsleuth.config.file` 指向）后，希望无需重启即可刷新配置；
- 同 JVM detach→re-attach 过程中，配置基线（defaults/file）可能发生变化，但缺少“显式 reload”能力；
- bootstrap 拦截器依赖 `BootstrapMonitorConfigStore` 以避免高频 sysprop 读取，因此 reload 后需同步 monitoring effective 配置到 Store，避免行为漂移。

## Goals & Success Criteria

- `config reload` 可重新读取 defaults + config file，并替换 `ProductionConfig` 的 file/default 基线；
- reload 不清除 runtime overrides（仍保持最高优先级），避免线上临时调试配置被意外抹掉；
- reload 后 best-effort 同步 monitoring 配置到 `BootstrapMonitorConfigStore`，保证“配置显示值”与拦截器实际行为一致；
- 不引入新的三方依赖，保持 bootstrap/foundation JDK-only 约束。

## Risk Assessment

- **Risk:** 部分组件可能在启动时缓存了配置（非每次读取），reload 不一定即时影响这些缓存。
  - **Mitigation:** 保持 reload 语义聚焦于 `ProductionConfig` 基线刷新；对需要强一致的模块后续再引入显式 reload hook（按需推进，避免一次性大改）。

