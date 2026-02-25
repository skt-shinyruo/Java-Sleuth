# Task List: Remove ProductionConfig Domain Getters

Directory: `helloagents/plan/202602221036_remove_productionconfig_domain_getters/`

---

## 1. foundation / config
- [√] 1.1 删除 `foundation/src/main/java/com/javasleuth/foundation/config/ProductionConfig.java` 中所有 domain getters（server/security/protocol/plugins/monitoring/logging），验证 why.md#core-scenarios
- [√] 1.2 清理 `ProductionConfig` 中因删除 getters 产生的未使用字段/导入/私有辅助逻辑，验证 why.md#core-scenarios

## 2. Consumer Migration
- [√] 2.1 迁移残留的 `ProductionConfig.getXxx()/isXxx()` 调用点到 `SleuthConfigSchema`/typed config，验证 why.md#core-scenarios
- [√] 2.2 更新/替换 `core/src/test/java/com/javasleuth/config/ProductionConfigGetterUsageGuardTest.java` 以防止 domain getters 回归，验证 why.md#core-scenarios

## 3. Security Check
- [√] 3.1 执行安全检查（敏感信息硬编码、权限绕过、危险命令逻辑回归），验证 why.md#risk-assessment

## 4. Documentation Update
- [√] 4.1 同步更新知识库：`helloagents/wiki/modules/config.md`（记录 domain getters 移除与替代读取方式）
- [√] 4.2 更新 `helloagents/CHANGELOG.md`（Added/Changed/Removed 记录）

## 5. Testing
- [√] 5.1 运行 `mvn test`，确保编译与单测通过
