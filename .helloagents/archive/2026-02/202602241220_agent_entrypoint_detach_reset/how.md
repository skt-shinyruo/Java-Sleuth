# Implementation Plan: Agent 入口 SSOT + detach reset

## 1. 入口收敛（SSOT）

- 新增 `core/src/main/java/com/javasleuth/core/agent/core/SleuthAgentEntrypointSupport.java`
  - 统一封装 premain/agentmain/shutdown 的公共逻辑
  - 负责 best-effort：sysprop rollback 注册、bootstrap monitoring 配置同步、runtime 启动与清理顺序等
- 调整 core 入口 `core/src/main/java/com/javasleuth/core/agent/core/SleuthAgentCore.java`
  - `premain/agentmain/shutdown` 改为委托 `SleuthAgentEntrypointSupport`
- 调整 container 入口 `container/src/main/java/com/javasleuth/container/SleuthAgentContainerEntrypoint.java`
  - 同样委托 `SleuthAgentEntrypointSupport`
  - 保留 container 专属的 classloader 释放动作（`closeOwnClassLoaderBestEffort()`）作为 finally 附加清理

## 2. shutdown 可重入兜底（singleton manager）

- 调整 `core/src/main/java/com/javasleuth/core/command/server/ShutdownCoordinator.java`
  - 将仍为 singleton 的 manager 关闭路径统一走 `shutdownInstance()`：
    - `AuthenticationManager.shutdownInstance()`
    - `DangerousCommandConfirmationManager.shutdownInstance()`
  - 覆盖 graceful 与 emergency 两条 shutdown 路径，确保 detach/shutdown 后不会遗留旧实例

## 3. bootstrap interceptor detach reset（静态状态治理）

为以下拦截器增加 `resetForDetach()`（以及必要时的 `resetMetrics()`）：

- `bootstrap/src/main/java/com/javasleuth/bootstrap/monitor/WatchInterceptor.java`
- `bootstrap/src/main/java/com/javasleuth/bootstrap/monitor/TraceInterceptor.java`
  - 引入 epoch 代际（`AtomicLong`）以惰性清理线程池 ThreadLocal
- `bootstrap/src/main/java/com/javasleuth/bootstrap/monitor/StackInterceptor.java`
- `bootstrap/src/main/java/com/javasleuth/bootstrap/monitor/TtInterceptor.java`
- `bootstrap/src/main/java/com/javasleuth/bootstrap/monitor/MonitorInterceptor.java`

## 4. 清理入口收口（core + test）

- 调整 `core/src/main/java/com/javasleuth/core/agent/runtime/AgentGlobalState.java`
  - detach/shutdown 时从“多个 unregisterAll/clearAll”切换为统一调用各 interceptor 的 `resetForDetach()`
- 调整 `core/src/test/java/com/javasleuth/test/SleuthTestState.java`
  - 测试全局清理逻辑同样委托 `resetForDetach()`，降低测试串状态与维护成本

## 5. 测试补齐

- 新增 `core/src/test/java/com/javasleuth/monitor/InterceptorDetachResetTest.java`
  - 覆盖 Watch/Trace/Stack/TT/Monitor 的 `resetForDetach()` 清理队列/缓存/计数/ThreadLocal 的核心语义

## 6. 文档与变更记录同步

- 同步知识库：
  - `helloagents/wiki/modules/agent.md`
  - `helloagents/wiki/modules/security.md`
  - `helloagents/wiki/modules/monitor.md`
- 更新变更记录：
  - `helloagents/CHANGELOG.md`
  - `helloagents/history/index.md`

## 7. 回归验证

- 运行 `mvn test`（全模块）验证编译、单测与 enforcer 规则通过

