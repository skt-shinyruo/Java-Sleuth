# 技术设计：CommandProcessor 组合根拆分与会话映射封装

## Technical Solution

### Core Technologies
- Java（现有工程，不引入 DI 框架）
- 现有模块：`command` / `command.server` / `command.session` / `security` / `monitoring`

### Implementation Key Points
1. **引入 Components 聚合对象**：把 config/security/metrics/audit/executor/registry/pipeline/bootstrapper/acceptor/clientHandler/shutdownCoordinator 等依赖打包，降低 `CommandProcessor` 字段扇出。
2. **引入 Factory/Builder 作为 composition root**：将单例获取（`getInstance()`）与全局副作用（如日志 provider、JobManager 配置）从 `CommandProcessor` 构造阶段迁移到 factory 或 `SleuthAgentCore`。
3. **封装 sessionByClient**：用 `ClientSessionIndex` 替代裸 Map；handler/执行器只通过该抽象读写会话映射。
4. **保持门面 API 稳定**：`CommandProcessor` 对外仍提供 `start/shutdown/shutdownGracefully/emergencyShutdown/restart/addShutdownHook` 等入口，内部仅做“状态持有 + 编排调用”。

## Architecture Design

```mermaid
flowchart TD
  A[SleuthAgentCore (composition root)] --> B[CommandProcessorFactory]
  B --> C[CommandProcessor (facade)]

  C --> D[ServerBootstrapper]
  C --> E[ConnectionAcceptor]
  C --> F[CommandClientHandler]
  C --> G[ShutdownCoordinator]

  F --> H[ClientSessionIndex]
  F --> I[CommandRegistry]
  F --> J[CommandPipeline]
  F --> K[MetricsCollector/AuditLogger/Security Managers]
```

## Architecture Decision ADR

### ADR-001: 将装配从 CommandProcessor 中剥离到 Factory/Components
**Context:** `CommandProcessor` 仍承担装配与横切依赖聚合，导致耦合中心与可测试性问题。  
**Decision:** 新增 `CommandProcessorFactory` + `CommandProcessorComponents`，`CommandProcessor` 仅保留 facade；默认构造路径委派到 factory。  
**Rationale:** 不引入外部 DI 框架前提下，用最小结构化方式降低构造复杂度与依赖扩散，并使测试可注入/可替换。  
**Alternatives:**
- 引入 Guice/Spring 等 DI：→ 拒绝原因：agent 场景过重、引入依赖与启动成本增加、风险面扩大。
- 新增 `CommandServer` 接口并重命名/迁移：→ 拒绝原因：变更面更大，需跨模块重写调用与测试，先做“组合根拆分”收益更大且风险更低。
**Impact:** 构造链路更清晰、测试更易注入；需要更新少量调用点与单测构造方式。

## Security and Performance

- **Security：**
  - 不改变现有握手/鉴权/授权/危险命令确认的语义与顺序（仅调整装配位置与依赖注入边界）。
  - 避免新增对 secret 的日志输出；factory 中如需生成/注入 secret，遵循现有 `ServerBootstrapper` 约束。
- **Performance：**
  - 不改变线程池参数来源（仍来自 `ProductionConfig`），仅将“创建位置”从 `CommandProcessor` 构造移动到 components/factory。
  - `ClientSessionIndex` 作为轻量封装，不引入锁竞争（内部仍可复用 `ConcurrentHashMap`）。

## Testing and Deployment

- **Testing：**
  - 单测：覆盖 `ClientSessionIndex` 的绑定/解绑与空值边界；覆盖 factory 构造产物的依赖一致性（不触发真实 bind）。
  - 回归：保留/调整现有 `CommandProcessor*Test` 与 `ProtocolClientIntegrationTest`，确保协议闭环与安全边界不变。
- **Deployment：**
  - 无部署流程变更；属于内部重构，要求 `mvn test` 通过。

