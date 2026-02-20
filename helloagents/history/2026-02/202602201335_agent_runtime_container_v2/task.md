# Task List: AgentRuntime 运行时容器化（未完成项续作 v2）

Directory: `helloagents/history/2026-02/202602201335_agent_runtime_container_v2/`

Ref: `helloagents/history/2026-02/202602201234_agent_runtime_container/`

---

## 1. foundation（security manager 注入优先，收敛 singleton 语义）
- [√] 1.1 调整 `foundation/src/main/java/com/javasleuth/security/AuthorizationManager.java`：构造注入改为严格非空（不再隐式回退到 getInstance）；新增 `createDefault()`；`getInstance()` 标注为 legacy/bridge-only；补齐 javadoc 说明
- [√] 1.2 调整 `foundation/src/main/java/com/javasleuth/security/RequestSecurityManager.java`：同上（严格注入 + createDefault + bridge-only getInstance）

## 2. Documentation Update
- [√] 2.1 更新 `helloagents/wiki/modules/security.md`：同步 “bridge-only singleton + 注入优先/strict constructor” 的约束与推荐用法
- [√] 2.2 更新 `helloagents/CHANGELOG.md`：记录本次 v2 的单例语义收敛变更点

## 3. Security Check
- [√] 3.1 执行安全检查（G9）：确保不引入明文 secret/token；确认 shutdown/清理对 RBAC/HMAC 行为无破坏；保持 bootstrap 边界不被扩大

## 4. Testing
- [√] 4.1 运行 `mvn test`（至少覆盖 `foundation/core/launcher`），确保改动不引入回归
