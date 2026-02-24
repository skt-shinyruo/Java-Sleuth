# Task List: 配置生命周期对齐（detach reset + bootstrap monitoring 同步）

Directory: `helloagents/plan/202602241259_config_detach_reset_monitor_sync/`

---

## 1. foundation：ProductionConfig detach reset
- [√] 1.1 为 `ProductionConfig` 增加 `resetInstanceForDetach()`，允许 detach→re-attach 重新加载 configFile/sysprop 基线

## 2. core：bootstrap monitoring 同步桥
- [√] 2.1 新增 `BootstrapMonitoringConfigSync`：从 `ProductionConfig.snapshot()` 解析强类型 monitoring 配置并同步到 `BootstrapMonitorConfigStore`
- [√] 2.2 attach 时使用同步桥填充 Store（入口侧）
- [√] 2.3 detach/shutdown 时在 sysprop rollback 后重置 `ProductionConfig` 单例

## 3. core：运行时 config 变更同步
- [√] 3.1 `ConfigCommand` 的 `set/remove/clear` 后调用同步桥，避免配置显示与拦截器实际行为漂移

## 4. tests：同步语义与测试隔离
- [√] 4.1 `SleuthTestState` 增强：清 `BootstrapMonitorConfigStore` + 重置 `ProductionConfig`
- [√] 4.2 新增 `BootstrapMonitoringConfigSyncTest`：覆盖 config set/remove/clear 的 Store 同步语义

## 5. Documentation Update
- [√] 5.1 更新知识库：`wiki/modules/config.md`、`wiki/modules/monitor.md`
- [√] 5.2 更新变更记录：`helloagents/CHANGELOG.md`

## 6. Testing
- [√] 6.1 运行 `mvn test`（全模块），确保回归通过

