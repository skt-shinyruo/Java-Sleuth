# Technical Design: Remove ProductionConfig Domain Getters

## Technical Solution

### Core Technologies
- Java + Maven 多模块工程
- `SleuthConfigSchema`（schema 驱动的配置读取）
- `SleuthConfigParser`（typed config 解析，用于展示/调试输出等）

### Implementation Key Points
- 在 `foundation/.../ProductionConfig.java` 中直接删除 domain getters（server/security/protocol/plugins/monitoring/logging 等）。
- 保留 `ConfigView` 相关的通用 getter（`getString/getInt/getLong/getDouble/getBoolean/getOrigin`）以及 `snapshot/runtime overrides/persist` 等必要能力。
- 通过编译错误定位残留调用点，迁移到 `SleuthConfigSchema.read(ConfigView)` 或相应 typed config。
- 更新 `ProductionConfigGetterUsageGuardTest`：
  - 目标：保证 `ProductionConfig` 不再暴露 domain getters（仅允许 `ConfigView` 通用 getter 与明确允许的运行时配置管理 API）。

## Security and Performance
- **Security:** 移除旧逻辑中的默认/兜底路径，统一由 schema 管控默认值与校验；避免安全配置在多处散落读取。
- **Performance:** 无显著影响；读取路径更直接，减少重复解析与分支判断。

## Testing and Deployment
- **Testing:** `mvn test`（阻断性），确保全模块编译与核心单测通过；必要时补充/更新守护测试。
- **Deployment:** 变更为破坏性 API 移除，发布说明需在知识库与 Changelog 中明确替代方案。

