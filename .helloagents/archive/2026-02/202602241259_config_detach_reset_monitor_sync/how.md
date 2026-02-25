# Implementation Plan: detach 重置 ProductionConfig + monitoring 同步桥

## 1) ProductionConfig detach reset（foundation）

- 在 `foundation/src/main/java/com/javasleuth/foundation/config/ProductionConfig.java` 新增：
  - `resetInstanceForDetach()`：将 singleton 置空，允许下次 attach 重新加载 configFile/sysprop 基线

## 2) bootstrap monitoring 同步桥（core）

- 新增 `core/src/main/java/com/javasleuth/core/agent/runtime/BootstrapMonitoringConfigSync.java`
  - 从 `ProductionConfig.snapshot()` 解析 `SleuthConfig`（强类型 + Schema 校验/归一化）
  - 将 effective monitoring 配置写入 `BootstrapMonitorConfigStore`

## 3) 生命周期编排与运行时变更同步

- `core/src/main/java/com/javasleuth/core/agent/core/SleuthAgentEntrypointSupport.java`
  - attach：使用同步桥填充 `BootstrapMonitorConfigStore`
  - detach/shutdown：在 sysprop rollback 后调用 `ProductionConfig.resetInstanceForDetach()`
- `core/src/main/java/com/javasleuth/core/command/impl/ConfigCommand.java`
  - `set/remove/clear` 后调用同步桥，确保 runtime overrides 立即影响 bootstrap 拦截器行为

## 4) 测试与全局清理

- `core/src/test/java/com/javasleuth/test/SleuthTestState.java`
  - 增加 `BootstrapMonitorConfigStore.clear()` 与 `ProductionConfig.resetInstanceForDetach()`，减少跨测试污染
- 新增测试 `core/src/test/java/com/javasleuth/bootstrap/monitor/BootstrapMonitoringConfigSyncTest.java`
  - 覆盖 config set/remove/clear 对 Store 的同步语义

## 5) 文档与变更记录

- 更新知识库：
  - `helloagents/wiki/modules/config.md`
  - `helloagents/wiki/modules/monitor.md`
- 更新变更记录：
  - `helloagents/CHANGELOG.md`

## 6) 验证

- 运行 `mvn test`（全模块）确保编译、单测与 JDK-only enforcer 规则通过

