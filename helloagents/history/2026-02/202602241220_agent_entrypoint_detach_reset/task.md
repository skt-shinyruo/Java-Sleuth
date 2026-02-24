# Task List: Agent 入口 SSOT + detach 清理收敛

Directory: `helloagents/plan/202602241220_agent_entrypoint_detach_reset/`

---

## 1. 入口收敛（SSOT）
- [√] 1.1 新增 `core/src/main/java/com/javasleuth/core/agent/core/SleuthAgentEntrypointSupport.java`：统一 premain/agentmain/shutdown 公共逻辑，降低 core/container 入口分叉风险
- [√] 1.2 调整 `core/src/main/java/com/javasleuth/core/agent/core/SleuthAgentCore.java`：入口方法委托 `SleuthAgentEntrypointSupport`
- [√] 1.3 调整 `container/src/main/java/com/javasleuth/container/SleuthAgentContainerEntrypoint.java`：入口方法委托 `SleuthAgentEntrypointSupport`，并保留 container 专属 classloader 释放动作

## 2. shutdown 可重入兜底（singleton manager）
- [√] 2.1 调整 `core/src/main/java/com/javasleuth/core/command/server/ShutdownCoordinator.java`：对 singleton manager 使用 `shutdownInstance()`（覆盖 graceful/emergency）

## 3. bootstrap interceptor detach reset（静态状态治理）
- [√] 3.1 Watch：`bootstrap/.../WatchInterceptor` 增加 `resetForDetach()`（清理队列/缓存/计数）
- [√] 3.2 Trace：`bootstrap/.../TraceInterceptor` 增加 `resetForDetach()`；ThreadLocal 采用 epoch 代际惰性清理，避免线程池残留
- [√] 3.3 Stack：`bootstrap/.../StackInterceptor` 增加 `resetForDetach()`
- [√] 3.4 TT：`bootstrap/.../TtInterceptor` 增加 `resetForDetach()`（清队列/清 records/重置计数）
- [√] 3.5 Monitor：`bootstrap/.../MonitorInterceptor` 增加 `resetForDetach()`（清 collectors）

## 4. 清理入口收口（core + test）
- [√] 4.1 调整 `core/src/main/java/com/javasleuth/core/agent/runtime/AgentGlobalState.java`：detach/shutdown 收口为调用各 interceptor 的 `resetForDetach()`
- [√] 4.2 调整 `core/src/test/java/com/javasleuth/test/SleuthTestState.java`：测试清理逻辑同样调用 `resetForDetach()`

## 5. 测试补齐
- [√] 5.1 新增 `core/src/test/java/com/javasleuth/monitor/InterceptorDetachResetTest.java`：锁定 resetForDetach 语义，避免后续回归

## 6. Documentation Update
- [√] 6.1 同步知识库：`helloagents/wiki/modules/agent.md`、`helloagents/wiki/modules/security.md`、`helloagents/wiki/modules/monitor.md`
- [√] 6.2 更新变更记录：`helloagents/CHANGELOG.md`、`helloagents/history/index.md`

## 7. Testing
- [√] 7.1 运行 `mvn test`（全模块），确保 bootstrap JDK-only 约束与单测通过

