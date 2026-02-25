# Implementation Plan: `config reload`

## 1) foundation：ProductionConfig 支持实例级 reload

- `ProductionConfig` 引入“已加载配置状态”的原子交换（LoadedConfigState）
- 新增 API：
  - `reloadConfiguration()`：重新从 defaults + config file 加载并替换基线配置（不清 runtime overrides）
  - `getLoadedConfigFile()` / `isLoadedFromFile()`：便于 status/reload 输出可解释

## 2) core：ConfigCommand 落地 reload 并保持 monitoring 行为一致

- `config reload`：
  - 调用 `ProductionConfig.reloadConfiguration()`
  - reload 后调用 `BootstrapMonitoringConfigSync` 同步 monitoring effective 配置到 `BootstrapMonitorConfigStore`
- `config status`：
  - 展示“实际已加载”的 config file 路径（避免仅看 sysprop 导致误判）

## 3) tests：回归保障

- 新增 `ProductionConfigReloadTest`：
  - 覆盖文件内容变化后 reload 生效
  - 覆盖 runtime overrides 在 reload 后仍保留（优先级仍最高）

## 4) docs & changelog

- 更新 `wiki/modules/config.md`：补充 `config reload` 语义与优先级说明
- 更新 `wiki/modules/monitor.md`：补充 reload 后 Store 同步点
- 更新 `helloagents/CHANGELOG.md`：记录新增 reload 能力

