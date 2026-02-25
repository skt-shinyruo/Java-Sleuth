# Implementation Plan: sysprop -> BootstrapMonitorConfigStore 同步

## 1) 触发点（命令侧）

- 在 `core/src/main/java/com/javasleuth/core/command/impl/SysPropCommand.java`：
  - 当 `sysprop set` 成功更新以下 key 时触发同步：
    - `sleuth.monitoring.watch.drop.on.full`
    - `sleuth.monitoring.trace.drop.on.full`
    - `sleuth.monitoring.trace.sample.rate`
    - `sleuth.monitoring.monitor.sample.rate`
  - 同步逻辑复用 `BootstrapMonitoringConfigSync.syncFromProductionConfigBestEffort(ProductionConfig.getInstance())`

## 2) 测试

- 新增 `core/src/test/java/com/javasleuth/bootstrap/monitor/SysPropMonitoringStoreSyncTest.java`
  - 覆盖：sysprop set 后 Store 值同步更新（并恢复原 sysprop，避免跨测试污染）

## 3) 文档与记录

- 更新 `helloagents/wiki/modules/monitor.md`：补充 `sysprop set sleuth.monitoring.*` 同步点
- 更新 `helloagents/CHANGELOG.md`

## 4) 验证

- 运行 `mvn test`（全模块）

