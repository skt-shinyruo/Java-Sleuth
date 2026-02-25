# Task List: Arthas 风格 ClassLoader 生命周期边界（attach/detach）

Directory: `helloagents/plan/202602211837_arthas_classloader_lifecycle/`

---

## 1. bootstrap（ClassLoader bridge / attach gate SSOT）
- [√] 1.1 新增 bootstrap 可见的 core ClassLoader registry/bridge（JDK-only，CAS 登记 + release/close），文件：`bootstrap/src/main/java/...`，verify why.md#requirement-classloader-boundary-scenario-detach-reattach
- [√] 1.2 为 bridge 增加幂等/并发保护与降级日志（append 失败导致 bridge 非 bootstrap loader 场景），文件：`bootstrap/src/main/java/...`，verify why.md#requirement-leak-free-classloader-close-scenario-close-without-thread-leak

## 2. agent（attach 入口改造：使用 bridge 管理生命周期）
- [√] 2.1 重构 `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`：移除不可重置的 `static ATTACHED` gate；append 后通过 bridge CAS 登记 core loader；失败回滚 close/unregister，verify why.md#requirement-classloader-boundary-scenario-detach-reattach
- [√] 2.2 attach 重入行为定义与实现：当 bridge 显示“已 attach”时给出明确提示/安全返回（避免重复注入），verify why.md#requirement-shutdown-simplification-scenario-shutdowncoordinator-focuses-on-instance

## 3. core（shutdown 闭环：通知 bridge 释放 ClassLoader）
- [√] 3.1 在 `core/src/main/java/com/javasleuth/agent/core/SleuthAgentCore.java` 的 shutdown 路径中，runtime close 完成后回调 bridge `onCoreShutdown(SleuthAgentCore.class.getClassLoader())`，verify why.md#requirement-classloader-boundary-scenario-detach-reattach
- [√] 3.2 在启动失败/部分初始化失败路径中补齐 best-effort 释放（避免“半 attach”残留 loader），verify why.md#requirement-leak-free-classloader-close-scenario-close-without-thread-leak

## 4. core/command（关闭编排收敛，弱化 shutdownInstance 补锅）
- [√] 4.1 调整 `core/src/main/java/com/javasleuth/command/server/ShutdownCoordinator.java`：优先保持 shutdown 编排只作用于注入实例；将 `shutdownInstance()` 调用移出核心路径（或删除），verify why.md#requirement-shutdown-simplification-scenario-shutdowncoordinator-focuses-on-instance

## 5. Security Check
- [√] 5.1 执行安全检查（G9）：确保不引入对外可调用的“任意卸载”入口；避免在日志输出 agentArgs 敏感信息；关闭/释放路径为 best-effort 幂等

## 6. Documentation Update
- [√] 6.1 更新知识库：`helloagents/wiki/arch.md`（补充 attach 生命周期边界与 ClassLoader 释放闭环）
- [√] 6.2 更新知识库：`helloagents/wiki/modules/command.md` / `helloagents/wiki/modules/security.md`（说明 shutdownInstance 的定位变化与 detach→re-attach 语义）

## 7. Testing
- [√] 7.1 增加单测：bridge register/release 幂等与并发边界（建议放在 core 的 test scope，避免 bootstrap 引入测试依赖），验证点：重复 attach 不会覆盖已有 loader；错误 loader 不会释放；release 后可再次 register
- [√] 7.2 增加冒烟用例（最小可行）：模拟一次 attach→shutdown→再次 attach 的 gate 行为（至少保证不会被永久拒绝），验证点：bridge 状态在 shutdown 后清空
