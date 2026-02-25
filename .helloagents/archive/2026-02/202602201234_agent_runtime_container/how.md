# Technical Design: AgentRuntime 运行时容器化（收口全局单例/静态状态）

## Technical Solution

### Core Technologies
- Java 8
- Maven multi-module（`agent` / `core` / `foundation` / `bootstrap` / `launcher`）

### Implementation Key Points

1. **新增运行时容器 `SleuthAgentRuntime`（core）**
   - 职责：集中持有 “一次 attach 的所有运行时对象”，并对外提供：
     - `start()`：启动 command processor（或由 core 入口负责启动线程，runtime 只持有组件）
     - `close()`：幂等关闭（统一 shutdown 顺序与 best-effort 语义）
   - 持有对象建议最少包含：
     - `Instrumentation`、`SleuthClassFileTransformer`、`CommandProcessor`
     - `ProductionConfig`、`AuditLogger`
     - `AuthenticationManager`、`AuthorizationManager`、`RequestSecurityManager`、`DangerousCommandConfirmationManager`
     - `ClientSessionRegistry`、`MetricsCollector`
2. **`SleuthAgentCore` 仅保留单一 runtime 引用**
   - 用 `AtomicReference<SleuthAgentRuntime>` 替代多个 static 字段（instrumentation/transformer/processor）
   - attach/re-attach：通过 CAS 确保幂等
   - shutdown：只做 “获取 runtime 并 close，然后清空引用” 的薄逻辑
3. **关闭编排（close order）作为 SSOT**
   - 统一 “先停服务端/线程池 → 再清理命令/会话/安全缓存 → 再回滚增强/移除 transformer → 最后清理日志/metrics” 的顺序
   - 现有 best-effort 清理点（watch/trace/monitor/tt/stack/vmtool session 等）收口到 runtime.close()，避免散落
4. **渐进式单例治理**
   - 不要求一次性移除所有 `getInstance()`，但要求：
     - 新/改动路径从 runtime 注入（或 factory 显式传参）
     - `getInstance()` 仅作为兼容入口，并在文档/注解层标记 “bridge only”
5. **测试隔离与回归**
   - 增加 “close 幂等 / detach→re-attach 不残留 hook 与缓存” 的单测
   - 提供测试工具类统一 reset 关键全局状态（仅测试 scope）

## Architecture Design

```mermaid
flowchart TD
    A[SleuthAgentCore (composition root)] -->|CAS set| R[SleuthAgentRuntime]
    R --> I[Instrumentation]
    R --> T[SleuthClassFileTransformer]
    R --> P[CommandProcessor]
    R --> C[ProductionConfig]
    R --> L[AuditLogger]
    R --> N[AuthenticationManager]
    R --> Z[AuthorizationManager]
    R --> S[RequestSecurityManager]
    R --> D[DangerousCommandConfirmationManager]
    R --> CSR[ClientSessionRegistry]
    R --> M[MetricsCollector]

    R -->|close()| SD[Shutdown Flow SSOT]
    SD --> P1[Stop server/threads]
    SD --> P2[Clear security/session caches]
    SD --> P3[Clear bootstrap registries]
    SD --> P4[Rollback enhancements + remove transformer]
    SD --> P5[Shutdown audit/metrics]
```

## Architecture Decision ADR

### ADR-001: 引入 `SleuthAgentRuntime` 作为单一运行时容器（收口生命周期）
**Context:** 当前全局单例/静态状态在多处散落初始化与清理，导致 detach→re-attach 与测试隔离成本显著上升。  
**Decision:** 在 `core` 引入 `SleuthAgentRuntime`，作为一次 attach 的运行时容器与关闭编排 SSOT；`SleuthAgentCore` 仅持有 runtime 引用并委托启动/关闭。  
**Rationale:**  
- 收口“全局状态”到最小边界，降低隐性耦合与可维护性成本  
- close() 统一顺序 + 幂等保证，减少 best-effort 清理遗漏  
- 不引入额外 DI 框架依赖，保持 agent 侧依赖面与隔离策略  
**Alternatives:**  
- 继续沿用“多处 static + 分散 best-effort 清理” → 拒绝原因：生命周期复杂度持续上升，且不可测试  
- 引入完整 DI 框架（Guice/Spring） → 拒绝原因：依赖面与启动复杂度上升，不符合 agent 隔离与最小依赖目标  
- 建立全局 Service Locator → 拒绝原因：本质仍是隐式全局状态，只是换了名字  
**Impact:**  
- 需要改造 `SleuthAgentCore` 入口与 shutdown 逻辑  
- 需要补充单测与文档，避免后续再次蔓延  

## Security and Performance

- **Security**
  - close() 内对 security manager cache 清理保持现有语义（rate limit/nonce/pending confirm 等）
  - 不新增明文 secret 持久化；避免把 config/security 对象提升到 bootstrap 可见范围
- **Performance**
  - runtime 引入仅增加一次对象聚合，不引入运行时额外热路径开销
  - shutdown 清理保持 best-effort，不阻塞目标 JVM 的正常退出路径

## Testing and Deployment

- **Testing**
  - 单测覆盖：`close()` 幂等、重复 attach 防护、detach→re-attach 不串安全缓存与注册表
  - 执行：`mvn test`
- **Deployment**
  - 仅涉及 agent-core 行为调整；按现有打包流程 `mvn package` 产出 jar-with-dependencies
