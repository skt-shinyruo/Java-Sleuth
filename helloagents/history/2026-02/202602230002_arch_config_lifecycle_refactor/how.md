# Technical Design: Agent 配置与生命周期治理（消除 sysprop 污染与全局状态泄漏）

## Technical Solution

### Core Technologies
- Java 8（目标运行时兼容）
- Maven 多模块（`bootstrap/foundation/agent/core/container/launcher`）
- Java Instrumentation + Attach API（动态注入）
- 隔离 ClassLoader：`URLClassLoader(urls, null)`（parent=null，避免与业务依赖碰撞）
- ServiceLoader + 插件目录 `URLClassLoader`（命令扩展）
- maven-enforcer-plugin（模块边界守护）

### Implementation Key Points

1. **配置通道收口：sysprop 仅作为外部 override，内部派生值不再写回 sysprop**
   - 在 `bootstrap` 增加 JDK-only 的轻量配置存储（例如 `BootstrapMonitorConfigStore`），由 `container` 在 attach 时写入监控相关的派生配置。
   - `BootstrapMonitorConfig` 读取优先级调整为：Store → sysprop（兼容外部）→ 默认值。
   - `AgentArgsApplier` 改为“可回滚”应用：记录本次写入的 key 与旧值，交由 runtime 在 shutdown 时恢复。

2. **以 attach-scoped runtime 为 SSOT：统一启动与关闭收口**
   - 以 `SleuthAgentRuntime.start/close` 作为 per-attach 生命周期对象的唯一入口与收口点：
     - 持有 instrumentation、transformer、command processor、jobs、session registry、plugin classloader 等资源
     - `close()` 负责有序关闭与幂等清理（包括触发 bootstrap 侧 registry 清理）
   - shutdown 路径统一：无论异常启动失败/stop 命令/外部触发，都最终进入同一条 close 链路。

3. **入口 SSOT：container 作为 composition root**
   - 明确 `agent` 只负责：隔离加载 `container` fat-jar 并调用其入口（`SleuthAgentContainerEntrypoint`）。
   - 对 core 内历史入口（如 `SleuthAgentCore`）进行收敛：保留兼容时改为委托 container，或在确认无引用后移除。

4. **命令子系统分层：Transport 与 Execution 解耦**
   - “socket accept loop / framing / 连接管理”作为 Transport 层（可用集成测试覆盖）
   - “认证/授权/危险命令确认/命令解析与执行”作为 Execution 层（可被纯单测覆盖）
   - `CommandProcessorFactory` 由“巨型参数列表”逐步演进为少量上下文对象（例如 `CommandRuntimeContext` + `CommandServices`），降低新增能力的扩散成本。

5. **插件机制：更强的生命周期与 API 稳定约束**
   - 插件目录加载的 `URLClassLoader` 必须由 registry/processor 生命周期持有并在 detach/shutdown 时关闭。
   - 插件 API 的稳定边界明确：对外只承诺 `CommandProvider`/`Command`/`CommandMeta` 的最小稳定面；新增可选能力通过默认方法扩展，避免破坏性改动。

## Architecture Design

```mermaid
flowchart TD
    L[launcher: SleuthLauncher] -->|Attach + agentArgs| A[agent: SleuthAgent (bootstrap-visible)]
    A -->|new URLClassLoader(containerJar, parent=null)| C[container: SleuthAgentContainerEntrypoint]
    C -->|start(inst)| R[core: SleuthAgentRuntime (per-attach SSOT)]
    R --> P[CommandProcessor/Transport]
    R --> T[SleuthClassFileTransformer]
    R --> S[Security Managers + Session Registry]
    P -->|loads| PL[Plugins (ServiceLoader / plugin dir URLClassLoader)]
    R -->|shutdown/close| X[Bootstrap Registries reset + ClassLoader registry release]
```

## Architecture Decision ADR

### ADR-010: attach-scoped 状态为 SSOT，sysprop 仅作为外部 override（并可回滚）
**Context:** 当前实现中存在 sysprop 派生写入、static registry 与 best-effort 清理的组合，使 detach→re-attach 的配置确定性与排障可观测性不足。  
**Decision:** 以 per-attach runtime 作为资源与配置的 SSOT；bootstrap 侧引入轻量 store 承载派生配置；sysprop 仅作为外部 override 与兼容通道，并对 agent 写入的 sysprop 提供回滚恢复。  
**Rationale:**
- sysprop 属于目标 JVM 全局状态，作为内部派生值 SSOT 会破坏隔离边界；
- per-attach runtime + registry reset 能把状态边界更清晰地绑定到隔离 classloader 生命周期；
- 保留 sysprop override 可避免破坏现有脚本/运维习惯，并允许紧急覆盖。  
**Alternatives:**
- 仅做 best-effort 清理，不调整配置通道 → 拒绝原因：配置漂移的根因仍在，问题会回归。  
- 完全移除 sysprop → 拒绝原因：兼容性与运维成本过高，缺少紧急覆盖通道。  
**Impact:** 需要调整 bootstrap 配置读取与 shutdown 回滚语义；增加少量测试与文档说明以避免误用。

## Security and Performance
- **Security:**
  - 保持现有认证/授权/危险命令确认语义不变；
  - 插件加载继续执行 allowlist + sha256 校验，失败必须拒绝并审计；
  - 配置/审计输出继续执行敏感信息遮罩（避免明文 secret 泄露）。
- **Performance:**
  - bootstrap 侧配置读取必须 O(1) 且无副作用；
  - registry/queue 必须有界或具备驱逐策略，避免在业务线程中造成内存增长。

## Testing and Deployment
- **Testing:**
  - 单测：覆盖 sysprop 回滚、bootstrap store precedence、pipeline/execution 逻辑（无真实 socket/instrumentation）。
  - 集成测试：覆盖 socket transport、会话断连清理、插件 classloader 关闭（必要时模拟）。
  - 回归：`mvn test`、`mvn verify`（含配置 schema 校验与 enforcer 边界）。
- **Deployment:**
  - 保持现有 jar 产物与启动脚本兼容（重点：agentArgs 与 sysprop override 语义保持可理解与可回滚）。
