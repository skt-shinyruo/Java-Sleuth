# Change Proposal: AgentRuntime 运行时容器化（收口全局单例/静态状态）

## Requirement Background

当前工程在 `core/` 与 `foundation/` 中存在较多 **全局单例 / static 可变状态**，并且这些状态在启动链路里被显式拉起、在 shutdown 链路里再 best-effort 到处“清表式”回收。该模式在功能迭代早期可快速推进，但随着模块复杂度增加，会显著抬升生命周期治理与测试维护成本。

典型证据点（非穷举）：

- `core/src/main/java/com/javasleuth/agent/core/SleuthAgentCore.java`
  - `instrumentation/commandProcessor/transformer` 为 `static`
  - 启动路径里显式初始化多个单例/全局对象：`ProductionConfig.getInstance()`、`AuditLogger.getInstance()`、`AuthenticationManager.getInstance()`、`DangerousCommandConfirmationManager.getInstance()`、`ClientSessionRegistry.getInstance()` 等
  - shutdown 路径里 best-effort 清理静态注册表：`WatchInterceptor.unregisterAllWatches()`、`TraceInterceptor.unregisterAllTraces()`……
- `foundation/src/main/java/com/javasleuth/security/AuthorizationManager.java`、`foundation/src/main/java/com/javasleuth/security/RequestSecurityManager.java`
  - 既支持构造注入，又提供 `getInstance() + shutdownInstance()`，并且构造时对 `null` 依赖默认回退到 `ProductionConfig.getInstance()` / `AuditLogger.getInstance()` 等
- `core/src/main/java/com/javasleuth/command/session/ClientSessionRegistry.java`
  - `private static final ClientSessionRegistry INSTANCE` 永久单例

风险影响：

- **可测试性**：单测/集成测容易“串状态”（尤其是 `Map/cache/ThreadLocal`），导致 flaky 与顺序依赖增强。
- **可替换性**：表面存在 DI（构造注入），但 `null` 回退到单例导致依赖来源不透明，架构边界不干净。
- **运行时可靠性**：为防止泄漏，shutdown 必须做大量“清表式”清理；任何遗漏都可能变成隐性 bug（尤其是 detach→re-attach/stop→restart）。

## Change Content

1. 引入明确的运行时容器对象 `SleuthAgentRuntime`（命名可调整），集中持有：
   - config/security/processor/transformer/interceptors/session/metrics 等实例引用
   - 并提供幂等 `close()` 作为统一关闭入口
2. 让 `SleuthAgentCore` 退化为更纯粹的 composition root：
   - 启动：构建 runtime → 启动 command processor
   - 关闭：委托 `runtime.close()`（统一顺序/幂等/清理）
   - 仅保留最小必要的 static（建议压缩为 1 个 `AtomicReference<SleuthAgentRuntime>`）
3. 单例策略收敛（渐进式，不要求一次性重构）：
   - `getInstance()` 逐步限制在 bridge/兼容层或极少数不可避免点
   - 新/改动代码路径：优先走工厂装配/构造注入，避免在业务对象构造阶段隐式拉起全局单例
4. 测试隔离：提供显式 reset/fixture，避免在测试里“靠记忆”手动 shutdownInstance。

## Impact Scope

- **Modules:** `core`、`foundation`、`bootstrap`（仅涉及现有静态注册表清理点的收口，不扩散 bootstrap 依赖面）
- **Primary Touch Points（预期）：**
  - `core/src/main/java/com/javasleuth/agent/core/SleuthAgentCore.java`
  - `core/src/main/java/com/javasleuth/command/CommandProcessor.java`（shutdown hook 绑定/解绑治理）
  - `core/src/main/java/com/javasleuth/command/session/ClientSessionRegistry.java`
  - `foundation/src/main/java/com/javasleuth/security/AuthorizationManager.java`
  - `foundation/src/main/java/com/javasleuth/security/RequestSecurityManager.java`

## Core Scenarios

### Requirement: 全局状态收口到运行时容器
**Module:** core / foundation
将“启动装配 + 生命周期关闭”收敛到单一 runtime 对象，降低全局状态扩散。

#### Scenario: attach/premain 启动只创建一次 runtime（幂等）
前置：同一 JVM 内多次调用 agent 入口  
- 若 runtime 已存在：拒绝重复 attach，并返回可解释日志
- 若 runtime 不存在：构建 runtime 并启动 command server

#### Scenario: shutdown/detach 时 runtime.close() 统一编排释放资源
前置：运行中或部分启动成功（中途异常）  
- `close()` 幂等、可重入（best-effort）
- 关闭顺序明确：先停网络/线程 → 再清理安全缓存/会话 → 再回滚增强/移除 transformer → 最后释放审计/metrics 等资源

### Requirement: detach→re-attach 不串状态
**Module:** core / bootstrap
支持同一 JVM 内 detach → re-attach，不因静态缓存/注册表残留产生不可预期行为。

#### Scenario: 关闭后再次 attach 不累积 shutdown hook/线程/注册表
前置：完成一次 attach → shutdown → attach  
- 不累积 JVM shutdown hook（可 best-effort remove）
- 不残留 watch/trace/monitor/tt/stack 等静态注册表
- 不残留 security manager cache（rate limit/nonce/pending confirm）

### Requirement: 测试隔离与依赖可替换
**Module:** core / foundation
允许测试在 JVM 进程内多次创建/关闭 runtime，或对全局单例做集中 reset，降低串状态。

#### Scenario: 单测可构造独立 runtime 或使用统一 reset 工具
前置：JUnit 测试执行  
- 每个 test case 结束后可一键清理（包括 shutdownInstance + registries）
- 测试可替换 config/audit/logger/security manager 实例，避免隐式依赖全局单例

## Risk Assessment

- **Risk:** shutdown 顺序变化导致边界行为变化（例如某些 registry 先清理/后清理的差异）
  - **Mitigation:** 保持现有 best-effort 语义；close() 内以现有 shutdown 顺序为基线并补齐缺口；增加幂等测试覆盖
- **Risk:** 兼容性风险（外部调用仍依赖 getInstance/静态入口）
  - **Mitigation:** 保留兼容入口，但把“推荐路径”切换到 runtime 注入；逐步迁移调用点，避免一次性大重构
- **Risk:** 多线程并发关闭/重复 attach 的竞态
  - **Mitigation:** `AtomicReference`/CAS 保证 runtime 单例语义；close() 内用 state flag 保证幂等
