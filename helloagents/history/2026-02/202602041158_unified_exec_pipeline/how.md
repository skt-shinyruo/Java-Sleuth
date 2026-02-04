# Technical Design: 统一命令执行与流式治理管道重构（Solution 2）

## Technical Solution

### Core Technologies
- Java 8 / Maven
- Attach API / Instrumentation
- ASM（自定义 `ClassWriter` 以增强 `COMPUTE_FRAMES` 可靠性）
- 现有协议：legacy / framed / binary
- 现有安全能力：HMAC（可选）、AuthorizationManager、DangerousCommandConfirmationManager

### Implementation Key Points
1. **目标类解析统一化**
   - 新增“已加载类解析器”：输入（classPattern、可选 loader 过滤条件），输出候选列表（包含 `Class<?>`、loader 标识、codeSource 等）。
   - 命令层（watch/trace/redefine）统一使用该解析器；当出现多候选时，默认拒绝执行并返回候选列表，要求用户显式选择（或使用兼容参数）。
   - 会话/回滚绑定：watch/trace session 保存 `Class<?> targetClass`（必要时弱引用 + loaderId 兜底）。

2. **插桩可靠性与失败策略**
   - 使用 loader-aware `ClassWriter`（重写 `getCommonSuperClass`，优先走目标 loader）。
   - 失败策略从“直接移除 enhancers”升级为“失败记录 + 冷却/可重试 + 可观测”，避免 watch/trace 突然静默失效。
   - 为失败策略提供配置项：例如 `enhancement.failure.policy`（cooldown/disable/remove）与 `enhancement.failure.cooldown.ms`。

3. **流式命令纳入 Pipeline**
   - 扩展 `CommandPipeline`：支持对 `StreamCommand` 的统一执行（同一套 executor/timeout/impact permit），并对每次 `sink.send()` 进行 `InputValidator.sanitizeOutput`（或等价治理）后再落到协议层。
   - `CommandProcessor` 的 framed/binary/legacy 三条路径使用同一个“执行与输出适配层”，保证行为一致。
   - legacy 文本模式引入可配置的 END marker（避免“无数据超时”启发式截断）。

4. **后台任务治理**
   - `JobManager` 从“每 job 新线程”升级为“有界 executor + 运行中并发上限 + 队列上限”。
   - 增加配置项：`jobs.max.running`（硬上限）、`jobs.executor.core/max/queue.capacity`（背压）。
   - `--bg` 命令在资源不足时返回明确错误（例如：server busy / too many running jobs）。

5. **插件/配置/文件编码一致性**
   - 插件加载策略：`plugins.enabled=false` 时不加载 classpath `ServiceLoader` provider；可新增 `plugins.serviceloader.enabled` 显式开启。
   - `config save` 支持持久化 runtime overrides（例如 `--include-runtime`），并确保敏感字段日志不落 stdout。
   - `mc` 默认 UTF-8（或提供 `--encoding`）；`redefine` 文件路径读取前统一走 `SecurityValidator.canReadFile`。

## Architecture Design
```mermaid
flowchart TD
    A[Client (Launcher)] --> B[CommandProcessor]
    B --> C[Protocol Adapter legacy/framed/binary]
    C --> D[Unified Execution Facade]
    D --> E[CommandPipeline]
    E --> F[Executor + Timeout + Impact Permit]
    E --> G[Output Governance sanitize/truncate]
    F --> H[Command/StreamCommand Impl]
    H --> I[Transformer/Enhancers]
    I --> J[Interceptors/Queues]
    J --> G
    G --> C
```

## Architecture Decision ADR

### ADR-004: 统一命令执行与流式输出治理管道
**Context:** 当前流式命令绕过 Pipeline，且多 ClassLoader/ASM 失败策略/后台任务模型分散在各处，导致稳定性与安全语义不一致。  
**Decision:** 引入“统一执行与输出治理”层：所有命令（含流式）都必须进入 `CommandPipeline` 的统一执行与治理流程；命令实现只关注业务逻辑与事件生成。  
**Rationale:**  
- 减少重复治理逻辑（timeout/并发/输出脱敏/END marker/断连清理）；  
- 将不确定性（ClassLoader 选择、插桩失败策略）收敛到可配置、可观测的统一组件；  
- 为后续扩展（插件命令、更多 stream 命令）提供一致基线。  
**Alternatives:**  
- 继续在各命令中打补丁 → 拒绝原因：逻辑继续分散且易漂移，长期维护成本高。  
- 将流式命令全部改为只返回 jobId（强制后台） → 拒绝原因：体验改变过大，且不利于交互式快速排障。  
**Impact:** 需要较大范围重构与回归；通过配置与兼容参数降低上线风险。

## Security and Performance
- **Security:**
  - 默认不加载 classpath provider（避免“隐式插件”风险）
  - 流式输出统一脱敏/截断（参数/返回值/异常摘要）
  - 关键高风险命令继续保持二次确认（DangerousCommandConfirmationManager）
  - 统一的文件路径校验与编码策略（mc/redefine）
- **Performance:**
  - 流式命令进入 executor 统一背压，避免连接线程执行重活
  - 后台任务并发上限，避免线程/队列失控
  - 插桩失败策略采用冷却/可重试，减少重复失败与日志刷屏

## Testing and Deployment
- **Testing:**
  - 单测：类解析器（多 loader 候选与选择）、插桩失败策略（冷却/计数）、Pipeline 对流式输出的治理（sanitize/timeout）
  - 回归脚本：覆盖 `watch/trace/tt` 的 framed/binary/legacy 三种协议输出边界；覆盖 `--bg` 并发上限与拒绝行为
- **Deployment:**
  - 建议分阶段启用：先保持兼容模式（不强制 strict loader），上线后在生产逐步打开更严格的默认策略
  - 配置模板与 ops 文档同步更新，明确新增开关与推荐值

