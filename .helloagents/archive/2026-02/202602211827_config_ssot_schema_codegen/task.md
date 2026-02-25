# Task List: 配置 SSOT 收敛（Schema 生成式单一事实源）与 Typed Config 全面迁移

Directory: `helloagents/plan/202602211827_config_ssot_schema_codegen/`

---

## 1. foundation：引入 Schema（SSOT）与 codegen/校验链路
- [√] 1.1 设计并落地 Schema DSL：新增 `foundation/src/main/java/com/javasleuth/config/schema/ConfigKey.java`、`foundation/src/main/java/com/javasleuth/config/schema/SleuthConfigSchema.java`，覆盖现有默认配置 keys 元信息，验证 why.md#req-schema-ssot
- [√] 1.2 实现 Schema 校验器：校验 key 唯一性、类型/默认一致性、敏感 key 标记完整性、fail-fast 策略分级完整性，验证 why.md#req-schema-ssot
- [√] 1.3 实现默认产物导出（或强一致校验）：
  - 导出/校验 `foundation/src/main/resources/sleuth-default.properties`
  - 导出/校验 `foundation/src/main/java/com/javasleuth/config/SleuthDefaults.java`
  验证 why.md#scn-generate-defaults
- [√] 1.4 Maven 集成：在 `foundation/pom.xml` 挂载 schema 校验（以及可选的生成）到构建阶段，并提供可重复执行的本地命令（例如 `mvn -pl foundation -DskipTests=true verify`），验证 why.md#scn-generate-defaults

## 2. foundation：Typed Config 覆盖扩展（以“代码实际消费”为准）
- [√] 2.1 扩展 typed model：新增 `PerformanceConfig/JobsConfig/MonitoringConfig/LoggingConfig/PluginsConfig` 等分组到 `foundation/src/main/java/com/javasleuth/config/model/`，并扩展聚合 `SleuthConfig`，验证 why.md#req-typed-coverage
- [√] 2.2 扩展 `SleuthConfigParser`：将默认/校验/归一化策略集中化，并对派生默认使用 Schema/Origin 语义统一处理，验证 why.md#scn-derived-default-consistency
- [√] 2.3 明确 key 分级与失败策略：protocol/security 显式非法值 fail-fast；非关键 key 采用 clamp/warn/fallback，并将策略写入 Schema 元数据，验证 why.md#req-typed-coverage

## 3. core：消费侧迁移（边界 parse + typed 传递）
- [√] 3.1 在服务端 composition root 收口 parse：在 `core/src/main/java/com/javasleuth/command/CommandProcessorFactory.java` 或等价边界处执行 `snapshot -> parse` 并将 typed config 作为依赖传递，验证 why.md#req-boundary-typed
- [√] 3.2 迁移 server 自举链路：
  - `core/src/main/java/com/javasleuth/command/server/ServerBootstrapper.java`（bind/安全校验/secret 自举）
  - `core/src/main/java/com/javasleuth/command/server/ConnectionAcceptor.java`（max connections/timeouts/过载拒绝策略）
  验证 why.md#req-boundary-typed
- [√] 3.3 迁移运行时链路中仍依赖 getter 的组件：
  - `core/src/main/java/com/javasleuth/monitoring/MetricsCollector.java`
  - `foundation/src/main/java/com/javasleuth/util/PerformanceOptimizer.java`
  - `foundation/src/main/java/com/javasleuth/util/MemoryOptimizer.java`
  验证 why.md#req-boundary-typed
- [√] 3.4 处理 bootstrap 同步点：将 `core/src/main/java/com/javasleuth/agent/core/SleuthAgentCore.java` 中对监控相关 sysprop 的同步改为读取 typed monitoring config（或临时使用 `ConfigView` 窄接口），验证 why.md#req-boundary-typed

## 4. launcher：保持 typed 边界一致并清理残留直读
- [√] 4.1 审计 launcher 是否存在 `ProductionConfig.getXxx()` 直读残留（含 attach 参数拼装、client 连接参数），全部替换为 typed config，验证 why.md#req-boundary-typed

## 5. ProductionConfig getter 退场与回归护栏
- [-] 5.1 将 `foundation/src/main/java/com/javasleuth/config/ProductionConfig.java` 的 `getXxx()` 标记弃用（并移除内置默认/校验逻辑，改为委托 typed 或 schema 规则），验证 why.md#req-remove-getters
- [√] 5.2 增加“禁止新增 getter 直读”的护栏（构建期规则或单测扫描）：
  - 目标：新增代码不得出现对 `ProductionConfig.getXxx()` 的调用
  - 建议落点：`core/src/test/java/...` 增加源码扫描型测试或 Maven Enforcer/Checkstyle 规则
  验证 why.md#scn-ban-getters
- [-] 5.3 当消费侧迁移完成后删除 getter，`ProductionConfig` 保留 `ConfigView/MutableConfig/snapshot`，验证 why.md#scn-stage-migration

## 6. Security Check
- [√] 6.1 安全检查：敏感 key 全量标记与脱敏输出一致；security/protocol 的 fail-fast 策略覆盖显式非法值；runtime override key 校验与 forbidden keys 策略保持一致（G9）

## 7. Documentation Update
- [√] 7.1 同步知识库：更新 `helloagents/wiki/modules/config.md`，明确“Schema 作为 SSOT、properties/fallback/typed 的生成/校验关系、getter 退场策略”

## 8. Testing
- [√] 8.1 扩展一致性回归：在 `core/src/test/java/com/javasleuth/config/DefaultConfigConsistencyTest.java` 基础上增加 schema 驱动的一致性校验（包含派生默认场景），验证 why.md#scn-generate-defaults 与 why.md#scn-derived-default-consistency
- [√] 8.2 增加关键边界测试：protocol/security 显式非法值 fail-fast；非关键 key clamp/warn 的行为可回归

---

## Notes
- 5.1 / 5.3（getter 标记弃用与最终删除）涉及大范围存量调用点收敛与行为语义对齐，本次执行先完成 Schema SSOT + 构建期强校验 + 回归护栏，确保“不再新增、可逐步减少”，后续可按模块分批迁移并再统一删除。
