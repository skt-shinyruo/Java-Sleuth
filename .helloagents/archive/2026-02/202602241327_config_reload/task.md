# Task List: 落地 `config reload`

Directory: `helloagents/plan/202602241327_config_reload/`

---

## 1. foundation：ProductionConfig reload
- [√] 1.1 引入 LoadedConfigState 并支持原子替换已加载配置基线
- [√] 1.2 新增 `reloadConfiguration()`（不清 runtime overrides）
- [√] 1.3 新增 `getLoadedConfigFile()` / `isLoadedFromFile()`（用于 status/reload 输出可解释）

## 2. core：ConfigCommand reload + monitoring 同步
- [√] 2.1 落地 `config reload`：reload 后同步 `BootstrapMonitorConfigStore`
- [√] 2.2 `config status` 展示“实际已加载”的 config file 路径

## 3. tests
- [√] 3.1 新增 `ProductionConfigReloadTest` 覆盖 reload 生效与 runtime overrides 保留

## 4. Documentation Update
- [√] 4.1 更新 `wiki/modules/config.md`、`wiki/modules/monitor.md`
- [√] 4.2 更新 `helloagents/CHANGELOG.md`

## 5. Testing
- [√] 5.1 运行 `mvn test`（全模块）验证通过

