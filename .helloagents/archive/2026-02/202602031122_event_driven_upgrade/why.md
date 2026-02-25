# 变更提案：事件驱动通信重构与诊断能力加固（Solution 3）

## 需求背景

当前 Java-Sleuth 已具备较完整的诊断指令集与字节码增强框架，但在“生产可用性、可维护性、以及极端负载下的稳定性”方面存在结构性风险与体验断层，主要体现在：

1. **并发与资源保护不足**：`CommandProcessor` 使用 `ThreadPoolExecutor + LinkedBlockingQueue(无界)`，在多客户端/高频命令/长耗时命令叠加时，会造成队列无限增长，进一步引发内存上涨、延迟抖动甚至 OOM；同时 `CallerRunsPolicy` 在无界队列场景下很难形成有效背压。
2. **安全默认值与启动体验冲突**：默认 `security.mode=hmac`，但默认 `security.hmac.secret` 为空；服务端会拒绝启动，导致“直接起 agent/服务端”路径不可用，强依赖 Launcher attach 时的 bootstrap 行为，运维排障成本高。
3. **会话隔离弱**：`watch/trace/tt` 等增强是 JVM 级全局副作用；若会话断开未清理，规则可能残留，跨会话互相影响，且容易出现“无人消费→队列堆积/丢弃”的隐性成本。
4. **缓存语义不一致**：既有 `CommandPipeline` 的 meta-cacheable 缓存，也有 `DashboardCommand/MemoryCommand` 等命令内部直接使用 `PerformanceOptimizer` 缓存；是否缓存、缓存粒度（会话/全局）、TTL 归属不清晰，增加维护风险。
5. **重型命令生产风险控制偏粗**：`getAllLoadedClasses` 全量扫描、`heapdump`、`jad`、热更新等具备明显峰值/停顿风险；现有“dangerous+限速”的治理不够细粒度，缺少统一的影响评估与执行窗口控制。

## 变更内容

1. 引入**事件驱动（Netty/NIO）传输层**，将命令通道从 BIO `ServerSocket` 演进为事件驱动 IO，天然具备背压与更清晰的连接生命周期回调。
2. 将命令服务拆分为“**Transport(网络) / Session(会话) / Pipeline(执行) / Instrumentation(增强)**”四层，减少耦合、明确责任边界。
3. 通过会话生命周期管理，实现 **watch/trace/tt 规则的会话归属**与**断连自动清理**，降低全局副作用残留风险。
4. 统一缓存入口与语义：**以 `CommandPipeline + CommandMeta` 为唯一缓存决策入口**，清理命令内部重复缓存（或显式做会话命名空间）。
5. 对重型命令建立统一的“**影响等级 + 二次确认 + 并发/速率限制 + 可观测**”治理框架。
6. 修复 HMAC 默认行为的断层：提供“**loopback 下自洽启动**”与“**更清晰的引导/报错**”，并保留生产安全边界（非 loopback 严格限制）。

## 影响范围

- **Modules：**
  - `com.javasleuth.command`（命令服务、执行管道、协议/会话适配）
  - `com.javasleuth.security`（握手/HMAC 规则、危险命令确认、默认策略）
  - `com.javasleuth.monitor` / `com.javasleuth.enhancement`（watch/trace/tt 注册与清理机制）
  - `com.javasleuth.launcher`（连接与引导提示）
  - `com.javasleuth.util`（缓存/性能/限速/连接池等工具）
  - `pom.xml`（新增/调整依赖）
- **APIs：** 对外命令行保持尽量兼容；协议层可能增加版本/字段以支持会话资源治理与更好的错误码。
- **Data：** 不引入持久化数据结构变更；新增的会话资源映射为内存态。

## 核心场景

### Requirement: R1-concurrency-backpressure
**Module:** command/transport
在高并发与长耗时命令下，服务端必须具备可控内存上限与明确背压策略。

#### Scenario: S1-flood-protection
当客户端持续快速发送命令，服务端应优先返回“过载/排队过长”错误（可观测），而不是无限排队导致内存膨胀。

### Requirement: R2-hmac-startup-self-contained
**Module:** security/config/launcher
默认安全策略必须“可启动且可解释”，避免空 secret 导致启动失败的断层体验。

#### Scenario: S2-loopback-auto-secret
当 `security.mode=hmac` 且 secret 为空，并且 `server.bind.address` 为 loopback 时：服务端生成临时 secret 并完成启动；客户端可通过引导/协商拿到会话信息并正常签名请求。

### Requirement: R3-session-isolation-and-auto-cleanup
**Module:** command/session + monitor/enhancement
watch/trace/tt 等增强规则必须可追溯到创建它的会话，并在会话结束时自动清理。

#### Scenario: S3-disconnect-cleanup
客户端断连后，该会话创建的 watch/trace/tt 规则应被自动注销；新会话不应收到旧会话的数据或承担旧会话的残留成本。

### Requirement: R4-cache-single-source-of-truth
**Module:** command/pipeline + util/perf
缓存策略必须统一入口与语义，避免“命令内部缓存 + 管道缓存”叠加产生难以推理的实时性与边界问题。

#### Scenario: S4-cache-visibility
维护者应能通过 `CommandMeta` 一眼判断某命令是否缓存、缓存粒度（会话/全局）与 TTL，并且实际行为与之保持一致。

### Requirement: R5-heavy-command-governance
**Module:** command/pipeline + security
对全量扫描/heapdump/jad/热更新等高风险命令提供统一的影响等级、二次确认与并发/速率限制。

#### Scenario: S5-safe-heavy-ops
在生产环境执行高风险命令时：必须明确提示影响、要求确认 token，并限制同时执行数量与频率；在过载或窗口不满足时返回可解释错误。

## 风险评估

- **风险：架构级重构引入兼容性风险**（协议/连接/线程模型变化可能影响现有脚本与运行方式）
  - **缓解：** 引入 `server.transport=bio|netty` 的灰度开关，保留 BIO 回退路径；先做到协议兼容，再逐步演进。
- **风险：新增第三方依赖（Netty）带来 CVE/版本兼容成本**
  - **缓解：** 在实施阶段锁定 Netty 4.1.x 具体版本并做 CVE 检查与回归测试；最小化依赖面（优先拆分到必要的模块）。
- **风险：会话清理策略不当导致误删/泄漏**
  - **缓解：** 以“会话资源归属表 + 显式 ownerId”实现清理，避免依赖线程上下文；补充断连/异常路径测试用例。
