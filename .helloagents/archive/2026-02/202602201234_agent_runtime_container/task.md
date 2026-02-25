# Task List: AgentRuntime 运行时容器化（收口全局单例/静态状态）

Directory: `helloagents/history/2026-02/202602201234_agent_runtime_container/`

---

## 1. core（运行时容器与入口收口）
- [√] 1.1 新增运行时容器 `core/src/main/java/com/javasleuth/agent/runtime/SleuthAgentRuntime.java`：聚合 inst/transformer/processor/security/session/metrics，并提供幂等 `close()`；验证 why.md#requirement-全局状态收口到运行时容器-scenario-shutdowndetach-时-runtimeclose-统一编排释放资源
- [√] 1.2 重构 `core/src/main/java/com/javasleuth/agent/core/SleuthAgentCore.java`：用单一 `AtomicReference<SleuthAgentRuntime>` 替代多处 static 可变字段；启动/关闭委托给 runtime；验证 why.md#requirement-全局状态收口到运行时容器-scenario-attachpremain-启动只创建一次-runtime幂等
- [√] 1.3 调整 `core/src/main/java/com/javasleuth/command/CommandProcessor.java`：保存 shutdown hook thread 引用并在 shutdown 路径 best-effort `Runtime.removeShutdownHook(...)`，避免 detach→re-attach 累积 hook；验证 why.md#requirement-detachre-attach-不串状态-scenario-关闭后再次-attach-不累积-shutdown-hook线程注册表

## 2. core/foundation（单例渐进治理：多实例优先）
- [√] 2.1 调整 `core/src/main/java/com/javasleuth/command/session/ClientSessionRegistry.java`：支持多实例（public 构造 + `shutdown()/clear()`），保留 `getInstance()` 兼容；验证 why.md#requirement-测试隔离与依赖可替换-scenario-单测可构造独立-runtime-或使用统一-reset-工具
- [-] 2.2 优化 security manager 的“注入优先”路径：已在 `helloagents/history/2026-02/202602201335_agent_runtime_container_v2/` 完成（strict 注入 + createDefault + bridge-only getInstance）

## 3. 测试隔离（防串状态）
- [√] 3.1 新增测试工具 `core/src/test/java/com/javasleuth/test/SleuthTestState.java`：集中封装 `shutdownInstance()` 与关键 registries 清理，供测试 `@After` 调用；验证 why.md#requirement-测试隔离与依赖可替换-scenario-单测可构造独立-runtime-或使用统一-reset-工具
- [√] 3.2 新增生命周期测试 `core/src/test/java/com/javasleuth/agent/core/SleuthAgentRuntimeLifecycleTest.java`：覆盖 close 幂等/重复 attach 防护/关键 cache 清理；验证 why.md#requirement-detachre-attach-不串状态-scenario-关闭后再次-attach-不累积-shutdown-hook线程注册表

## 4. Security Check
- [-] 4.1 执行安全检查（G9）：已在 `helloagents/history/2026-02/202602201335_agent_runtime_container_v2/` 完成

## 5. Documentation Update
- [-] 5.1 同步知识库：已在 `helloagents/history/2026-02/202602201335_agent_runtime_container_v2/` 完成（security.md + CHANGELOG 补齐）

## 6. Testing
- [√] 6.1 运行 `mvn test`，确保核心用例不因状态治理改动而回归（重点关注 concurrency/command lifecycle/security 相关测试）
