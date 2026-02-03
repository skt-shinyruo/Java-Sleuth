# Architecture Design

## Overall Architecture
```mermaid
flowchart TD
    A[CLI Launcher] --> B[Attach API]
    B --> C[Target JVM: SleuthAgent]
    C --> D[CommandProcessor Server]
    A --> X[HELLO/CONFIG Handshake]
    X --> Y[Binary/Framed Channel]
    D --> E[Command Implementations]
    E --> F[Transformer/Enhancers]
    F --> G[Interceptors]
    G --> H[Result Queue/Response]
```

## Tech Stack
- **Backend:** Java 8, Maven
- **Diagnostic:** Attach API, Instrumentation
- **Bytecode:** ASM
- **CLI:** JLine
- **Monitoring:** JMX（可选）

## Resource Governance / Backpressure
- **连接侧背压**：`CommandProcessor` 使用有界队列处理新连接，队列满会拒绝新连接（避免无限排队导致内存上涨）
  - `server.max.connections`：并发连接上限
  - `server.executor.queue.capacity`：连接处理线程池排队上限
- **命令执行侧背压**：`CommandPipeline` 使用有界命令执行线程池替代 `Executors.newCachedThreadPool`
  - `performance.command.executor.core/max/queue.capacity`：命令执行线程池与队列上限
  - 队列满时返回明确错误（避免线程膨胀与排队失控）
- **重型命令治理**：对 `impact=HIGH` 的命令统一二次确认与并发限制
  - `security.impact.high.confirm.enabled`
  - `security.impact.high.concurrent.limit`

## Core Flow
```mermaid
sequenceDiagram
    participant User
    participant Launcher
    participant JVM as Target JVM
    participant Agent
    participant Server as CommandProcessor
    User->>Launcher: 启动并选择 JVM
    Launcher->>JVM: Attach + loadAgent
    JVM->>Agent: agentmain()
    Agent->>Server: 启动命令服务
    User->>Launcher: 输入命令
    Launcher->>Server: HELLO/CONFIG 握手协商
    Launcher->>Server: Socket 发送命令（legacy/framed/binary）
    Server->>Agent: 执行命令/触发增强
    Agent-->>Server: 返回结果
    Server-->>Launcher: 输出响应
```

## Major Architecture Decisions
| adr_id | title | date | status | affected_modules | details |
|--------|-------|------|--------|------------------|---------|
| ADR-001 | Attach + Socket CLI 架构 | 2026-01-28 | ✅Adopted | launcher/agent/command | 待补充 |
| ADR-002 | 插件化命令与分帧协议并行兼容 | 2026-01-28 | ✅Adopted | command/launcher/security/enhancement/monitor | history/2026-01/202601281207_sleuth_plugin_stream/how.md#adr-002-插件化命令与分帧协议并行兼容 |
| ADR-003 | HELLO/CONFIG 握手 + 严格二进制帧 + 可选 HMAC | 2026-01-28 | ✅Adopted | launcher/command/security/config | history/2026-01/202601281301_sleuth_handshake_secure_frames/how.md |
