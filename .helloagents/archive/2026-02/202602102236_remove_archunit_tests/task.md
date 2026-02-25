# Task List: 移除 ArchUnit 架构守护测试（按团队偏好）

Directory: `helloagents/plan/202602102236_remove_archunit_tests/`

---

## 1. Remove ArchUnit Tests
- [√] 1.1 删除 `core/src/test/java/com/javasleuth/arch/LayeringRulesTest.java`
- [√] 1.2 移除 `core/pom.xml` 中 `com.tngtech.archunit:archunit-junit4` 测试依赖

## 2. Knowledge Base Sync
- [√] 2.1 更新 `helloagents/wiki/arch.md`：分层守护策略改为仅依赖 Maven 模块边界（不引入架构测试）
- [√] 2.2 更新 `helloagents/wiki/modules/command.md`：变更记录去除 ArchUnit 守护描述
- [√] 2.3 更新 `helloagents/CHANGELOG.md`：记录移除 ArchUnit 测试与依赖

## 3. Verification
- [√] 3.1 执行 `mvn test` 并通过
