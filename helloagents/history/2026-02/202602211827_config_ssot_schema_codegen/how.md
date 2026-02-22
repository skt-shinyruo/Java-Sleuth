# Technical Design: 配置 SSOT 收敛（Schema 生成式单一事实源）与 Typed Config 全面迁移

## Technical Solution

### Core Technologies
- Java 8（JDK-only，避免引入额外解析依赖作为基础约束）
- Maven（将 codegen/校验挂到 `generate-sources` 或 `process-resources` 阶段）
- 现有配置基础设施：`ConfigLoader` / `ProductionConfig` / `ConfigSnapshot` / `ConfigView` / `SleuthConfigParser`

### Implementation Key Points
1. **引入 Config Schema（SSOT）**
   - Schema 需要表达的不仅是“key=default”，还包括：
     - 类型：string/int/long/double/boolean/enum
     - 字面默认值（literal default）
     - 派生默认（derived default，依赖其他 key 或依赖 origin）
     - 约束：范围、非空、枚举集合、上限护栏（cap）
     - 敏感性：是否需要 mask（配合 `SensitiveKeyMasker`）
     - 失败策略：fail-fast / warn+fallback / clamp+warn（按 key 分级）
   - Schema 形式建议采用“代码即 Schema”的 DSL（便于表达派生默认与校验），例如：
     - `com.javasleuth.config.schema.ConfigKey<T>`
     - `com.javasleuth.config.schema.SleuthConfigSchema`（注册所有 keys + metadata）

2. **生成式产物（或强一致校验）**
   - 目标：任何默认/校验变更都只能改 Schema，其他产物要么由其生成，要么由其校验一致性。
   - 产物清单（建议最小闭环从 2.1 开始逐步扩展）：
     1) 2.1 `foundation/src/main/resources/sleuth-default.properties`
        - 作为“运维可读的默认配置清单”，但其内容不再手工维护（生成或受控）
     2) 2.2 `foundation/src/main/java/com/javasleuth/config/SleuthDefaults.java`
        - 作为“资源缺失时 fallback 默认集合”，由 Schema 生成，避免手写漂移
     3) 2.3 typed config model + parser
        - 方向 A（渐进）：先扩展现有 `SleuthConfig*` 手写模型，但默认/校验逻辑引用 schema 常量/策略；后续再生成 parser
        - 方向 B（彻底）：由 Schema 生成 `SleuthConfig` 聚合与各分组 `*Config`、以及对应 parser（包含派生默认与 fail-fast 策略）

3. **Typed Config 覆盖面扩展策略**
   - 以“代码实际消费”为准扩展 typed model，优先覆盖：
     - server：bind/port/max connections/timeouts/queue
     - protocol：mode/streaming/frame payload/text line max（含派生默认）
     - security：mode/authz/anonymous/session role/confirm policy
     - performance：线程池/command executor/timeout+cap/maintenance
     - jobs：retention + execution caps
     - monitoring：队列容量/drop 策略/采样率
     - logging：level/console/audit file path/performance logging
     - plugins：enable/serviceloader/allowlist/directory/strategy
   - 原则：**只要代码在运行时读取，就必须在 typed config 中可表达**，避免再次出现“typed 只覆盖一部分，剩下靠 getter”的断层。

4. **消费侧迁移：边界 parse + typed 传递**
   - 统一在边界处：
     - `ProductionConfig.snapshot()` 得到 `ConfigSnapshot`
     - `SleuthConfigParser.parse(snapshot)` 得到 typed config（后续扩展为全量 typed）
   - 之后的业务链路只允许使用 typed config（或注入窄 `ConfigView`，但原则上仅用于极少数暂未覆盖的过渡点）
   - 重点边界（建议优先迁移）：
     - server 启动自举：bind/安全校验/secret 自举
     - connection accept：max connections/timeouts/queue saturation
     - request loop/handshake：协议上限/升级策略
     - 线程池与 jobs：executor 配置与背压上限
     - metrics/monitor：采样率与队列策略

5. **ProductionConfig.getXxx() 退场路线**
   - Stage 1（兼容）：getter 标记 `@Deprecated`，并移除其内部默认/校验逻辑（改为委托 typed config 的结果或 schema 的规则）
   - Stage 2（收口）：引入构建期/单测护栏，禁止新增 getter 引用；按模块逐步替换
   - Stage 3（移除）：当全量消费都迁移到 typed config 后删除 getter，`ProductionConfig` 保留：
     - `ConfigView` / `MutableConfig`
     - `snapshot()`（作为 request/session 边界一致性入口）

## Architecture Design

```mermaid
flowchart TD
    Schema[Config Schema (SSOT)] --> Codegen[Schema Validator / Codegen]
    Codegen --> DefaultsRes[/sleuth-default.properties]
    Codegen --> Fallback[SleuthDefaults.java]
    Codegen --> Typed[Typed Models + Parser]

    DefaultsRes --> Loader[ConfigLoader]
    Loader --> ProdCfg[ProductionConfig]
    ProdCfg --> Snap[ConfigSnapshot]
    Snap --> Parser[SleuthConfigParser]
    Parser --> Consumers[core/launcher Consumers]
```

## Architecture Decision ADR

### ADR-001: 采用 Schema + 生成式（或强一致校验）作为配置默认/校验/派生规则的单一事实源
**Context:** 当前默认值与校验分散在 properties / fallback / getter / parser，且消费侧 typed 与 getter 混用，导致边界不一致风险不可避免（新增 key、调整默认、引入派生默认时尤甚）。  
**Decision:** 引入 Config Schema 作为 SSOT，并将默认资源、fallback 默认集合、typed model/parser 的默认/校验规则收敛到 Schema 驱动的生成/校验链路中；同时制定消费侧迁移与 getter 退场计划。  
**Rationale:**
- 从结构上消除“多处手写默认/校验”的漂移源
- 将关键兼容边界（protocol/security）的 fail-fast 策略集中定义，避免局部逻辑静默降级
- 为文档与工具链（例如 config get 输出 origin/派生信息、配置键索引、变更审计）提供统一元数据来源  
**Alternatives:**
- 方案 1（仅集中到 parser）：仍存在 `/sleuth-default.properties` 与 fallback、以及非 parser 消费点的漂移风险 → 拒绝原因：无法从根上消除“非 typed 消费”与“默认资源/代码默认”的双源
- 方案 2（保留 getter 但委托 typed）：能缓解漂移，但 getter API 长期存在会诱导继续使用 → 拒绝原因：难以形成强约束与最终收口
**Impact:**
- 构建链路增加 schema 校验/生成步骤，需要完善测试与故障回滚策略
- 需要对 “literal default vs derived default” 做清晰标注，避免运维误读

## Security and Performance
- **Security:**
  - Schema 需标记敏感 key（secret/password/token），并与现有脱敏输出策略对齐
  - 关键安全边界（`security.mode` 等）对“显式非法值”必须 fail-fast，避免静默降级导致误暴露
  - 对 runtime overrides 的 key 校验策略与 forbidden keys 规则保持集中与一致
- **Performance:**
  - 解析成本控制：typed config 在边界 parse 一次，避免在热点循环重复解析字符串/数字
  - Schema/codegen 仅发生在构建期，不引入运行时额外依赖

## Testing and Deployment
- **Testing:**
  - Schema 与 `/sleuth-default.properties`、`SleuthDefaults` 的一致性：golden test / round-trip test
  - “派生默认一致性”测试：在不同 origin 情况下（DEFAULT/FILE/SYS/RUNTIME）确保 parser 行为一致
  - “禁止 getter 直读”护栏：构建期规则或单测扫描，防止回退到 stringly getter
  - 关键边界 fail-fast 策略测试：protocol/security 显式非法值必须失败
- **Deployment:**
  - 按阶段启用：先引入 schema 校验（不生成），再切换生成默认资源与 fallback，最后扩展 typed 全覆盖并移除 getter

