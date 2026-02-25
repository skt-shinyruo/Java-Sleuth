# Change Proposal: Arthas 风格 ClassLoader 生命周期边界（attach/detach）

## Requirement Background

当前实现里，部分组件以 **全局单例/静态状态** 方式存在，并且包含自建后台线程与缓存（例如 `AuthenticationManager` 的会话清理调度器、`AuditLogger` 的后台消费线程与 writer、`DangerousCommandConfirmationManager` 的 pending confirm 缓存）。

为了支持同 JVM 的 detach→re-attach 与测试隔离，`core/.../ShutdownCoordinator` 不得不同时：

1. shutdown 注入实例（正确的生命周期语义）
2. 再 best-effort 调用 `shutdownInstance()` 清理全局单例（对抗单例副作用）

这是一种“用关闭编排对抗全局副作用”的信号：关闭路径复杂、脆弱、难以保证覆盖全部泄漏点；并且随着更多组件引入单例/静态状态，关闭编排会持续膨胀。

对标 Arthas 的工程实践：**把“隔离 ClassLoader”作为一次 attach 的生命周期边界**。当 detach/stop 发生时，通过严格的关闭顺序确保核心线程/Transformer/Socket/缓存都被释放，然后关闭并释放该 ClassLoader，使核心实现的 static/singleton 随 ClassLoader 一起消亡，从模型上降低“手工 reset 全局状态”的必要性。

## Change Content

1. 引入 bootstrap 可见的“核心 ClassLoader 生命周期桥接/注册表”，用于登记本次 attach 创建的 core 隔离 ClassLoader，并提供在 shutdown 后的释放/关闭能力
2. 重构 bootstrap agent 的 attach gate：不再使用不可重置的 `static ATTACHED` 作为终身开关，而是与“core ClassLoader 是否仍被登记”为准（从而允许 detach 后 re-attach）
3. core shutdown 路径在完成 `SleuthAgentRuntime.close()` 后，回调 bootstrap 桥接以关闭/释放 core ClassLoader（避免 JAR 锁、避免静态状态跨 attach 残留）
4. （可选，配合收敛“关闭编排对抗单例”信号）将 `ShutdownCoordinator` 中的 `shutdownInstance()` best-effort 清理从核心关闭编排中移除或下沉到更合适的边界（优先依赖“ClassLoader 释放 + runtime close”实现 reset）

## Impact Scope
- **Modules:** `agent` / `bootstrap` / `core`（`foundation` 原则上不需要改造为多实例，先通过 ClassLoader 边界获得生命周期收口）
- **Files (expected):**
  - `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`
  - `bootstrap/src/main/java/...`（新增：core classloader registry/bridge，JDK-only）
  - `core/src/main/java/com/javasleuth/agent/core/SleuthAgentCore.java`
  - `core/src/main/java/com/javasleuth/command/server/ShutdownCoordinator.java`（可选：弱化/移除 shutdownInstance 逻辑）
- **APIs:** 无对外 API 变更（内部生命周期语义调整）
- **Data:** 无

## Core Scenarios

### Requirement: classloader-boundary
**Module:** agent / bootstrap / core
以隔离 ClassLoader 作为“一次 attach”的生命周期边界；detach 后核心实现的 static/singleton 不应跨 attach 残留。

#### Scenario: detach-reattach
前置：同一 JVM 内发生 attach → shutdown/detach → 再次 attach
- shutdown/detach 完成后，core 隔离 ClassLoader 被关闭并释放（`URLClassLoader.close()` + 清引用）
- 再次 attach 会创建新的 core ClassLoader 并重新加载 core 入口
- 不需要依赖 `ShutdownCoordinator` 广泛调用 `shutdownInstance()` 来“模拟重启”

### Requirement: shutdown-simplification
**Module:** core/command
关闭编排应聚焦“本次 attach 的实例资源”（network/executor/pipeline/security instance），避免引入与生命周期无关的全局 reset 逻辑。

#### Scenario: shutdowncoordinator-focuses-on-instance
前置：调用 graceful/emergency shutdown
- `ShutdownCoordinator` 只对注入实例执行 shutdown（幂等 best-effort）
- 全局状态的清理由更合适的边界承担：bootstrap 侧静态注册表由 `AgentGlobalState` 统一 reset；core 侧静态状态由 ClassLoader 释放自然消亡

### Requirement: leak-free-classloader-close
**Module:** core
core ClassLoader 可被安全关闭，不应因残留线程/Transformer/Hook/外部强引用而无法释放。

#### Scenario: close-without-thread-leak
前置：core 内创建过后台线程、Transformer 已注册、服务端已启动
- shutdown 顺序确保：先停网络/线程池/后台任务，再移除 Transformer，再等待关键线程退出
- 关闭后不残留非预期线程；在 Windows 上不持有 core JAR 文件锁（best-effort）

## Risk Assessment
- **Risk:** core ClassLoader 关闭过早/关闭时仍有线程运行 → 可能导致 NPE/LinkageError 或资源未释放，进而影响二次 attach
  - **Mitigation:** 将“ClassLoader 释放”放在 `SleuthAgentRuntime.close()` 之后；并明确关闭顺序（stop server/pipeline/executors → remove transformer → join threads → close classloader），并补充单测覆盖 bridge 的幂等与并发边界
- **Risk:** bootstrap bridge 类未由 BootstrapClassLoader 加载（append 失败或加载时机不当）导致 core/agent 看到不同类副本，状态无法共享
  - **Mitigation:** bootstrap agent 在 append 成功后再访问 bridge；bridge 内加入 `ClassLoader == null` 断言/告警（best-effort），并在无法保证时降级为“仅单次 attach”

