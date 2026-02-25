# Task List: 压缩全局状态蔓延面（v3）

Directory: `helloagents/history/2026-02/202602201353_compress_global_state_v3/`

---

## 1. core（command 侧注入优先，减少 getInstance() 回退）
- [√] 1.1 调整 `core/src/main/java/com/javasleuth/command/CommandRegistry.java`：构造注入路径改为 strict non-null（auth/dangerousConfirm 不再隐式回退 getInstance），保留 legacy 构造器但标注 deprecated/bridge-only
- [√] 1.2 调整 `core/src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`：同上（auth/dangerousConfirm strict 注入，legacy/bridge-only fallback 收敛）
- [√] 1.3 调整 `core/src/main/java/com/javasleuth/command/CommandPipeline.java`：避免构造器内部调用 `DangerousCommandConfirmationManager.getInstance()`；新增显式注入入口（并更新测试/装配）

## 2. foundation（InputValidator 注入优先，减少隐式单例依赖）
- [√] 2.1 调整 `foundation/src/main/java/com/javasleuth/security/InputValidator.java`：构造注入改为 strict non-null（不再隐式回退到 `ProductionConfig.getInstance()` / `AuditLogger.getInstance()`）；新增 `createDefault()`；`new InputValidator()` 作为 legacy/bridge-only（或直接替换为显式构造）

## 3. Tests（消除测试内 singleton 依赖，避免串状态）
- [√] 3.1 更新相关单测（`core/src/test/java/com/javasleuth/security/InputValidatorTest.java`、`core/src/test/java/com/javasleuth/command/*`）：改用显式构造（`createDefault()` 或注入实例），避免 `AuthorizationManager.getInstance()` 等隐式依赖

## 4. Documentation Update
- [√] 4.1 更新 `helloagents/wiki/modules/command.md` 与 `helloagents/wiki/modules/security.md`：补充 “bridge-only singleton” 约束与推荐装配方式
- [√] 4.2 更新 `helloagents/CHANGELOG.md`：记录本次 v3 的注入优先/构造 strict 变更点

## 5. Security Check
- [√] 5.1 执行安全检查（G9）：确保不引入明文 secret/token；命令执行链路与 RBAC/HMAC/confirm 语义不被破坏；bootstrap 边界不被扩大

## 6. Testing
- [√] 6.1 运行 `mvn test`，确保全量回归通过
