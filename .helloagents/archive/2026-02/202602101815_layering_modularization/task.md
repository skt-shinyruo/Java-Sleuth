# Task List: 恢复分层边界（Maven 多模块化 + ArchUnit 守护）

Directory: `helloagents/plan/202602101815_layering_modularization/`

---

## 1. Build Structure（Maven 多模块）
- [√] 1.1 在根 `pom.xml` 增加 `foundation` 模块声明，verify why.md#requirement-foundation-module-boundary #scenario-forbidden-imports-fail-the-build
- [√] 1.2 新增 `foundation/pom.xml`（jar 模块）并补齐其依赖（按迁移后的实际使用最小化），verify why.md#requirement-foundation-module-boundary
- [√] 1.3 调整 `core/pom.xml`：显式依赖 `foundation`，并迁移/拆分依赖到正确模块（避免“依赖都堆在 core”），verify why.md#requirement-foundation-module-boundary

## 2. Code Migration（迁移低层到 foundation）
- [√] 2.1 迁移 `com.javasleuth.util` 到 `foundation` 模块并修复编译，verify why.md#requirement-foundation-module-boundary
- [√] 2.2 迁移 `com.javasleuth.config` 到 `foundation` 模块并修复编译，verify why.md#requirement-foundation-module-boundary
- [√] 2.3 迁移 `com.javasleuth.security` 到 `foundation` 模块并修复编译，verify why.md#requirement-foundation-module-boundary
- [√] 2.4 迁移 `com.javasleuth.data` 到 `foundation` 模块并修复编译，verify why.md#requirement-foundation-module-boundary

## 3. Break Cycles（拆环与解耦）
- [√] 3.1 将 `CommandMeta` 从 `command` 下沉到 `foundation`（靠近 `security` SSOT），并批量更新引用点，verify why.md#requirement-security-does-not-depend-on-command #scenario-commandmeta-lives-in-foundation
- [√] 3.2 调整 `AuthorizationManager` / `DangerousCommandConfirmationManager` 等安全组件：不再 import `command`，verify why.md#requirement-security-does-not-depend-on-command
- [√] 3.3 `SleuthLogger` 去掉对 `CommandContextHolder` 的编译期依赖，命令上下文改由 `SleuthLogContext` 注入，verify why.md#requirement-util-logger-does-not-import-command-context #scenario-context-via-sleuthlogcontext-only
- [√] 3.4 将仅被 `watch/trace` 使用的条件解析/评估逻辑移出 `util`（例如移动到 `command` 或 `monitoring` 侧），避免 `util -> monitor`，verify why.md#requirement-foundation-module-boundary
- [√] 3.5 `stop` 命令通过注入的生命周期回调触发 shutdown（不再 import `SleuthAgent`），verify why.md#requirement-command-stop-without-agent-dependency #scenario-stopcommand-uses-injected-lifecycle

## 4. Guardrails（ArchUnit 分层守护）
- [√] 4.1 在 `core` 测试引入 ArchUnit，添加规则：禁止 `command -> agent` 依赖，verify why.md#requirement-command-stop-without-agent-dependency
- [√] 4.2 添加规则：顶层包 slices `com.javasleuth.(*)..` 必须无循环依赖（beFreeOfCycles），verify why.md#requirement-foundation-module-boundary

## 5. Security Check
- [√] 5.1 执行安全检查（G9）：确认拆分与下沉不会绕过 Auth/AuthZ/DangerousConfirm，且不会引入敏感信息明文输出/存储

## 6. Documentation Update（知识库同步）
- [√] 6.1 更新 `helloagents/wiki/modules/util.md` 的依赖与分层约束说明（SSOT）
- [√] 6.2 更新 `helloagents/wiki/modules/security.md`：`CommandMeta` SSOT 归属与依赖方向（SSOT）
- [√] 6.3 更新 `helloagents/wiki/modules/command.md` / `helloagents/wiki/modules/agent.md`：stop/shutdown 解耦与分层约束（SSOT）
- [√] 6.4 更新 `helloagents/wiki/arch.md`：补充 ADR-008 索引与模块依赖图（SSOT）
- [√] 6.5 更新 `helloagents/CHANGELOG.md`：记录本次重构（按语义版本/Changelog 规范）

## 7. Testing & Packaging
- [√] 7.1 运行 `mvn test`（父工程 + 子模块）并修复本次变更引入的问题
- [√] 7.2 运行 `mvn package`，验证 `*-jar-with-dependencies.jar` 产物可用，verify why.md#requirement-packaging-compatibility #scenario-launcher-locates-agent-jar-after-refactor

---

## Execution Summary
- `mvn test` ✅
- `mvn package` ✅
- `mvn verify` ✅（包含 animal-sniffer Java 8 API 校验）
