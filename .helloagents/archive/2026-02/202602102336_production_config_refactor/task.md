# Task List: 降低 ProductionConfig 架构吸力（拆分职责 + 明确运行时可变语义）

Directory: `helloagents/plan/202602102336_production_config_refactor/`

---

## 1. Config API（只读视图 + 语义模型）
- [√] 1.1 新增 `ConfigView`（只读）与 `ConfigOrigin`（来源枚举）到 `foundation`，verify why.md#requirement-config-semantics #scenario-priority-is-explainable
- [√] 1.2 新增 `ConfigSnapshot`（不可变快照，可选启用）到 `foundation`，verify why.md#requirement-config-semantics #scenario-request-snapshot-consistency-optional

## 2. Runtime Overrides（可写覆盖 + 变更可追溯）
- [√] 2.1 新增 `RuntimeConfigStore` + `ConfigChange`（记录 old/new/source/ts，敏感值脱敏摘要），verify why.md#requirement-runtime-overrides-are-traceable #scenario-config-set-is-audited
- [√] 2.2 收敛写入入口：`ConfigCommand` / bootstrap 写入使用统一 API（带 source），verify why.md#requirement-runtime-overrides-are-traceable #scenario-config-set-is-audited

## 3. Decompose ProductionConfig（拆职责，退化为 Facade）
- [√] 3.1 提取 `SensitiveKeyMasker`（key 识别 + value mask），替换 `maskIfSensitive` 逻辑，verify why.md#requirement-god-config-decomposition #scenario-productionconfig-becomes-facade
- [√] 3.2 提取 `LogPathResolver/PidUtil`（默认日志路径 + pid），替换 `defaultLogPath/currentPid/appendPidSuffix`，verify why.md#requirement-god-config-decomposition #scenario-productionconfig-becomes-facade
- [√] 3.3 提取 `ConfigPersister`（saveConfiguration/includeRuntimeOverrides），verify why.md#requirement-god-config-decomposition #scenario-productionconfig-becomes-facade
- [√] 3.4 提取 `ConfigLoader`（load+validate），并确保“禁用键校验”语义可控（默认行为需确认），verify why.md#requirement-config-semantics #scenario-priority-is-explainable
- [√] 3.5 `ProductionConfig` 实现 `ConfigView` 并委托给上述组件，保持现有 `getXxx()` 短期兼容，verify why.md#requirement-god-config-decomposition #scenario-productionconfig-becomes-facade

## 4. Reduce Implicit Dependencies（减少 getInstance 吸力）
- [√] 4.1 将关键链路（CommandProcessor/CommandPipeline/HandshakeNegotiator 等）依赖收敛为构造注入 `ConfigView`（禁止新增 `getInstance()` 使用点），verify why.md#requirement-config-semantics #scenario-priority-is-explainable
- [√] 4.2 梳理并减少 `ProductionConfig.getInstance()` 调用点（目标：核心路径尽量只在 boundary 使用），verify why.md#requirement-god-config-decomposition #scenario-productionconfig-becomes-facade

## 5. Security Check
- [√] 5.1 执行安全检查（G9）：确认运行时覆盖不会绕过 `security.*` 关键边界，敏感字段输出/日志均脱敏，必要时引入可写 key allowlist

## 6. Documentation Update（知识库同步）
- [√] 6.1 更新 `helloagents/wiki/modules/config.md`：明确 ConfigView/MutableConfig/RuntimeOverrides 语义与追溯能力（SSOT）
- [√] 6.2 更新 `helloagents/wiki/arch.md`：补充 ADR-009 索引与依赖收敛策略（SSOT）
- [√] 6.3 更新 `helloagents/CHANGELOG.md`：记录本次配置层重构

## 7. Testing & Verification
- [√] 7.1 新增/补齐测试：配置优先级、runtime overrides 追溯、save includeRuntime、脱敏规则
- [√] 7.2 运行 `mvn test`（父工程 + 子模块）
- [√] 7.3 运行 `mvn package` / `mvn verify`（确保打包与 Java 8 API 校验不回归）
