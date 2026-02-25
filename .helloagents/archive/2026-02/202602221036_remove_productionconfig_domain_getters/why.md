# Change Proposal: Remove ProductionConfig Domain Getters

## Requirement Background
历史方案包 `202602211827_config_ssot_schema_codegen` 已迁移到 `helloagents/history/2026-02/`，其中涉及 `ProductionConfig` 的旧式 domain getters（`getServerPort()` / `isAuditLoggingEnabled()` 等）在当时流程中未直接删除（为了阶段性迁移）。

当前需求明确：**不使用 `@Deprecated`，而是直接删除这些旧 getters，并确保旧逻辑彻底移除**。消费侧统一通过 `SleuthConfigSchema.read(ConfigView)` 或 `SleuthConfigParser` 的 typed config 读取配置，避免继续扩散 SSOT 不一致来源。

## Change Content
1. 删除 `ProductionConfig` 中所有面向业务域的 getters（server/security/protocol/plugins/monitoring/logging 等）。
2. 将残留的 getter 调用点迁移到 `SleuthConfigSchema`/typed config。
3. 更新测试/守护机制，确保后续不会重新引入 domain getters。
4. 同步更新知识库文档与变更记录。

## Impact Scope
- **Modules:** `foundation`, `core`, `launcher`
- **Files:** `foundation/.../ProductionConfig.java`，以及可能残留调用点与相关测试
- **APIs:** 移除 `ProductionConfig` 的 domain getter 公共方法（破坏性变更）
- **Data:** 不涉及数据迁移

## Core Scenarios

### Requirement: Domain Getters Removal
**Module:** config / foundation
删除 `ProductionConfig.getXxx()/isXxx()` 等 domain getters，避免继续作为配置 SSOT 的入口。

#### Scenario: Compile-time Enforcement
当有人尝试再次新增/调用 domain getter：
- 期望：编译或守护测试失败，强制改回 schema/typed 读取方式。

### Requirement: Consumer Migration
**Module:** security / core / launcher
所有配置读取统一走 schema/typed，不依赖 `ProductionConfig` 旧接口。

#### Scenario: Runtime Behavior Consistency
相同配置 key 在不同模块读取结果一致：
- 期望：通过 `SleuthConfigSchema` 读取的默认值/类型转换策略统一，输出一致。

## Risk Assessment
- **Risk:** 删除公共方法导致编译失败或运行期行为变化
- **Mitigation:** 以 `mvn test` 作为阻断性验证；逐个修复编译错误并补齐迁移点；更新知识库标注破坏性变更与替代 API

