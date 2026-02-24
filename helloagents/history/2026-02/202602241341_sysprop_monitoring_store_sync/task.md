# Task List: `sysprop set sleuth.monitoring.*` 同步 Store

Directory: `helloagents/plan/202602241341_sysprop_monitoring_store_sync/`

---

## 1. 实现：sysprop set 触发同步
- [√] 1.1 在 `SysPropCommand` 中识别 monitoring sysprop keys，并在 set 成功后 best-effort 同步到 `BootstrapMonitorConfigStore`

## 2. tests
- [√] 2.1 新增 `SysPropMonitoringStoreSyncTest` 覆盖同步语义与 sysprop 恢复

## 3. Documentation Update
- [√] 3.1 更新 `wiki/modules/monitor.md` 与 `helloagents/CHANGELOG.md`

## 4. Testing
- [√] 4.1 运行 `mvn test`（全模块）验证通过

