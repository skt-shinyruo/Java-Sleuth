# Change Proposal: 配置 SSOT 收敛（Schema 生成式单一事实源）与 Typed Config 全面迁移

## Requirement Background
当前配置系统存在“SSOT 不唯一/默认值与校验规则漂移”的结构性风险：默认值与校验/归一化逻辑分散在 `/sleuth-default.properties`、fallback 默认集合、`ProductionConfig` 的大量 getter 默认值与局部校验、以及 `SleuthConfigParser` 的强类型解析与派生默认中；消费侧又同时存在 typed config（在连接/会话边界 parse）与 `ProductionConfig` getter 直读（在装配与运行时链路各处），形成典型的“半迁移”状态。

在这种结构下，后续新增配置项或调整默认/校验时，很容易出现：
- 同一 key 在不同调用点的默认值不一致（或越界处理策略不同）
- 派生默认（例如依赖另一个 key 的默认）在 server/launcher/握手边界表现不一致
- 关键兼容边界（协议/安全）在“显式非法值”与“缺省/缺失”时行为不一致

本次需求目标是：只做架构/风险分析与方案拆解，给出一条可落地的、可回归验证的“单一事实源”方案与迁移路线：所有消费侧统一走 `ConfigSnapshot/ConfigView -> SleuthConfigParser -> typed config`，并逐步禁止/移除 `ProductionConfig.getXxx()` 直读。

## Change Content
1. 引入 **Config Schema**（配置键/类型/默认/约束/敏感性/派生默认规则的单一事实源），并以此生成（或校验生成）代码与资源产物
2. 以 Schema 生成/驱动 typed config：扩展强类型模型覆盖当前代码实际消费的配置（server/protocol/security/performance/jobs/monitoring/logging/plugins 等）
3. 迁移消费侧：在边界处一次性 parse typed config，并通过依赖注入/参数传递传播；禁止新增 `ProductionConfig.getXxx()` 直读
4. 对 `ProductionConfig.getXxx()` 做分阶段退场：先降级为内部委托/兼容层并打上弃用标记，再通过回归护栏确保无剩余引用后移除
5. 建立回归护栏：一致性测试 + “禁止 getter 直读”的静态/构建期校验 + 文档同步

## Impact Scope
- **Modules:**
  - `foundation`（config/schema/codegen/typed model）
  - `core`（服务端自举/装配/运行时链路全面迁移 typed）
  - `launcher`（保持 typed 边界一致，补齐遗漏的直读点）
- **Files (expected):**
  - `foundation/src/main/java/com/javasleuth/config/schema/*`
  - `foundation/src/main/java/com/javasleuth/config/model/*`（扩展）
  - `foundation/src/main/resources/sleuth-default.properties`（改为生成/受控）
  - `foundation/src/main/java/com/javasleuth/config/SleuthDefaults.java`（改为生成/受控）
  - `foundation/src/main/java/com/javasleuth/config/ProductionConfig.java`（getter 退场/弃用/最终移除）
  - `core/src/main/java/**`（消费侧迁移：ServerBootstrapper/ConnectionAcceptor/Factory/监控/性能等）
- **APIs:** 内部 API（typed model、schema 生成工具、ConfigView/ConfigSnapshot）调整；对外 CLI/协议不直接新增接口
- **Data:** 无持久化数据结构变更（仅配置语义/默认/校验收敛）

## Core Scenarios

<a id="req-schema-ssot"></a>
### Requirement: Schema 成为默认/校验/派生规则的 SSOT
**Module:** foundation/config
集中定义配置键的：类型、字面默认值、派生默认规则、约束（范围/枚举/非空）、敏感性（mask）与变更策略。

<a id="scn-generate-defaults"></a>
#### Scenario: 生成/校验默认资源与 fallback 默认集合
前置：Schema 已定义全部 keys 与默认语义  
- `sleuth-default.properties` 与 `SleuthDefaults` 由 Schema 生成或强一致校验
- CI 单测/构建期校验保证：默认字面值不漂移；新增 key 必须在 Schema 中声明

<a id="req-typed-coverage"></a>
### Requirement: Typed Config 覆盖所有实际消费的配置
**Module:** foundation/config/model
强类型模型扩展到覆盖当前代码实际读取的配置类别，并将默认/校验/归一化规则集中在 parser（或由 schema 生成 parser）。

<a id="scn-derived-default-consistency"></a>
#### Scenario: 派生默认在 server/launcher/握手边界一致
前置：用户仅显式配置部分 key（其余缺省）  
- 派生默认规则只存在于 Schema/Parser 单处
- 无论在 launcher 还是 core，在边界 parse 后得到的 typed config 一致

<a id="req-boundary-typed"></a>
### Requirement: 消费侧统一“边界 parse + typed 传递”
**Module:** core/launcher
在连接/会话/启动装配等边界一次性 `snapshot -> parse`，其后以 typed config 作为唯一读入口（避免在循环/局部逻辑里到处 read key）。

<a id="scn-ban-getters"></a>
#### Scenario: 禁止新增 ProductionConfig getter 直读
前置：开发提交包含新代码  
- 构建期（或单测）会拒绝新增对 `ProductionConfig.getXxx()` 的引用
- 只允许依赖 `ConfigView`（窄接口）或 typed model

<a id="req-remove-getters"></a>
### Requirement: ProductionConfig.getXxx() 逐步退场并最终移除
**Module:** foundation/config
将 `ProductionConfig` 从“全能配置中心 + 默认/校验散落点”收敛为 `ConfigView/MutableConfig` 与 `snapshot()` 的实现载体。

<a id="scn-stage-migration"></a>
#### Scenario: 分阶段迁移与兼容窗口
前置：迁移进行中  
- Stage 1：getter 标记弃用（并移除默认/校验逻辑，委托 typed）
- Stage 2：消费侧迁移完成后，删除 getter，保留窄接口与 snapshot

## Risk Assessment
- **Risk:** 引入 schema/codegen 增加构建复杂度，生成逻辑错误可能导致默认/校验被整体带偏  
  **Mitigation:** 以“校验优先、生成次之”的策略渐进落地；保留现有一致性测试并扩展为 golden tests；初期仅生成/校验默认资源与 fallback，typed parser 仍由人工维护并逐步过渡到生成。
- **Risk:** “字面默认值”与“语义默认/派生默认”边界不清会造成运维认知偏差  
  **Mitigation:** Schema 显式区分 literal default 与 derived default；文档与 `config get` 输出 origin/派生信息（必要时在 typed model 中保留来源元信息）。
- **Risk:** fail-fast 策略调整可能引入兼容性变化（尤其协议/安全）  
  **Mitigation:** 将 key 分级：关键边界（protocol/security）对“显式非法值” fail-fast；非关键 key 采用 clamp + warn + metrics；所有策略在 schema 中集中定义并可回归。
