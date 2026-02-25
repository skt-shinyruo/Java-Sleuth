# Technical Design: 修复 5 个核心实现问题（协议/安全/插桩重构）

## Technical Solution
### Core Technologies
- Java 8
- Attach API（`com.sun.tools.attach`）
- Socket 通信（文本/帧/二进制帧）
- ASM（字节码增强）
- JLine（交互式 CLI）
- HMAC-SHA256（请求完整性/防重放，现有 `RequestSecurityManager`）

### Implementation Key Points
- 引入“统一传输层（Transport）+ 协议状态机（State Machine）”：
  - 同一底层 `InputStream/OutputStream` 上实现 UTF-8 行读取/写入与二进制帧读取/写入
  - 明确状态：`TEXT` → `HANDSHAKE` → `FRAMED/BINARY`（可回退到 legacy）
  - 消除 `BufferedReader/PrintWriter` 与 `DataInputStream/DataOutputStream` 混用导致的缓冲残留风险
- 改造 SleuthLauncher：
  - JVM 列表过滤后重新编号（展示与选择一致）
  - 连接地址不再写死 `localhost`，基于配置/协商解析连接 host（对 `0.0.0.0` 做安全回退）
  - 可选：在 attach 时生成一次性 HMAC secret 并作为 agentArgs 注入（避免落盘 secret）
- 安全与权限收敛：
  - 非回环 bind 时不允许 `security.mode=off`（拒绝启动或强制切换到 hmac）
  - 对危险命令做“参数级”权限判断（例如 `sysprop set` / `sysprop <key> <value>` 需要 ADMIN）
  - 默认关闭匿名 viewer（或至少在非回环 bind 时强制关闭）
- AuthenticationManager 重构：
  - 使用结构化的失败计数与锁定窗口（attempts + firstSeen + lockedUntil）
  - 可靠解析客户端标识（处理 `/ip:port`、IPv6、unknown 等）
  - 清理线程按时间淘汰而不是全量 clear
- 插桩与日志：
  - `SleuthAgent` 的命令线程设为 daemon，避免阻止 JVM 退出
  - `SleuthClassFileTransformer` 放开对 CGLIB 代理类的“一刀切”过滤（仍保留对 `$$Lambda$` 等噪音类的过滤）
  - 插桩日志通过 `logging.level` 控制（INFO 不刷屏，DEBUG 才打印每次 transform）

- 输入校验与文件权限判断修复：
  - `InputValidator` 参数校验需与命令真实格式一致：
    - `redefine <class-name> <class-file-path>`：第 1 个参数校验类名/模式，第 2 个参数校验 `.class` 路径
    - `mc <source-file-path>`：校验 `.java` 路径
    - `heapdump [filename]`：使用统一的“可写路径”校验
  - `SecurityValidator.canAccessFile` 拆分为更明确的语义：
    - `canReadFile(path)` / `canWriteFile(path)`，并正确处理相对路径（父目录为空视为当前目录）

- 审计/控制台日志脱敏：
  - `AuditLogger.logCommandExecution` 不记录明文敏感参数（auth 密码、secret、token、session 等）
  - `ProductionConfig.setRuntimeConfig` 输出时对敏感 key 做脱敏（避免 `config set security.hmac.secret xxx` 直接打印）
  - 统一脱敏策略：基于 key/command 名称，输出 `***` 或仅保留长度/前后缀

- 资源治理与 DoS 防护：
  - 在 CommandProcessor accept 阶段落实 `server.max.connections`（超限立即拒绝并关闭 socket）
  - 在 Transport 层为文本行设置最大长度（与 `protocol.frame.max.payload` 对齐或单独配置）
  - 在 CommandPipeline/CommandProcessor 对命令执行加超时控制：使用 `Future.get(timeout)`，超时则返回错误并记录 metrics/audit

- 性能维护策略修正：
  - 禁用默认定时 `System.gc()`（改为配置 `performance.maintenance.force_gc=false`）
  - maintenance 仍可做缓存清理与线程池调优，但要避免引入 STW 风险

- 配置/文档一致性：
  - 移除/更正 `security.mode=tls` 等“未实现但出现在配置注释/文档”的内容
  - 对不支持的模式给出明确错误提示（而不是静默 fallback）

## Architecture Design
```mermaid
flowchart TD
    A[SleuthLauncher CLI] -->|Attach API loadAgent| B[Target JVM]
    B --> C[SleuthAgent]
    C --> D[CommandProcessor ServerSocket]
    A -->|Socket Connect| D

    subgraph Transport Layer
      T1[Utf8LineCodec] --> T2[Handshake]
      T2 --> T3[Framed Protocol]
      T2 --> T4[Binary Frames]
    end

    D --> Transport Layer
    A --> Transport Layer

    D --> P[CommandPipeline]
    P --> R[CommandRegistry]
    R --> I[Commands Impl]
    I --> E[SleuthClassFileTransformer]
    E --> M[Watch/Trace Interceptors]
```

## Architecture Decision ADR
### ADR-001: 引入统一 Transport + 状态机替代混用流封装
**Context:** 当前实现同时使用 `BufferedReader/PrintWriter` 与 `DataInputStream/DataOutputStream` 在同一 socket 上切换，存在缓冲残留导致的协议升级不稳定风险。  
**Decision:** 统一使用 `InputStream/OutputStream` + 自研 UTF-8 行编解码与二进制帧编解码，并由明确的状态机控制握手/升级流程。  
**Rationale:** 改动面虽大，但可从根源消除不确定性，并为后续扩展（TLS、压缩、更多帧类型）提供稳定底座。  
**Alternatives:**
- 继续沿用现有 Reader/Writer 与 DataStream 混用 → 拒绝原因：难以彻底保证无缓冲残留
- 引入 Netty/NIO 重写网络层 → 拒绝原因：依赖与复杂度大幅提升，超出当前项目收益比
**Impact:** 需要同步修改客户端与服务端协议实现，并补齐握手/升级/断连/streaming 的测试与文档。

## API Design
（CLI/命令层面的“接口”变更）
- `sysprop`：
  - 保留只读查询能力（viewer 可用）
  - 写入能力改为显式子命令（如 `sysprop set <key> <value>`）并要求更高权限
- `auth`：
  - 若继续保留，则仅允许 loopback 连接使用；非回环场景建议使用安全模式 + 预共享 secret（或禁用 auth）

## Data Model
无持久化数据结构变更。

## Security and Performance
- **Security:**
  - 非回环 bind 强制启用请求完整性保护（hmac），避免明文控制暴露
  - 危险命令（redefine/retransform/mc/heapdump/sysprop-set）需要更高权限与更严格输入校验
  - 会话与 nonce 缓存做上限与淘汰，避免内存膨胀
- **Performance:**
  - 传输层复用 buffered stream，减少对象创建
  - 插桩日志默认降噪，避免高频 `System.out` 影响吞吐
  - 代理类插桩可通过配置/白名单控制，避免大范围增强带来额外开销
  - 禁用默认 `System.gc()`，减少 STW 抖动；如需启用必须显式配置并记录审计

## Testing and Deployment
- **Testing:**
  - 新增单测覆盖：握手协商、二进制升级、认证锁定窗口、sysprop 写入权限
  - 运行 `mvn test` 回归现有测试
  - 增加本地端到端回归：启动 `TestApplication` / `EnhancedTestApplication` 后 attach 进入会话执行核心命令
- **Deployment:**
  - 更新 `README.md` 与 `docs/usage/commands.md`：说明新的连接/安全默认与命令变更
  - 保留 legacy/off 兼容开关以便平滑迁移
