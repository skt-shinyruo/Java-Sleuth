# Task List：事件驱动通信重构与诊断能力加固（Solution 3）

Directory: `helloagents/plan/202602031122_event_driven_upgrade/`

---

## 1. Transport 层（BIO → Netty 事件驱动）
 - [-] 1.1 引入 `TransportServer` 抽象：新增 `src/main/java/com/javasleuth/transport/TransportServer.java`，并在 `src/main/java/com/javasleuth/command/CommandProcessor.java` 中以接口持有；新增 `server.transport` 配置读取（`src/main/java/com/javasleuth/config/ProductionConfig.java`），verify why.md#requirement-r1-concurrency-backpressure + why.md#requirement-r3-session-isolation-and-auto-cleanup（本轮未落地 Netty/BIO 双栈改造，先完成资源治理与安全自洽）
 - [-] 1.2 抽取现有 BIO 实现为 `BioTransportServer`：把 `ServerSocket/Socket` accept + 读写协议从 `src/main/java/com/javasleuth/command/CommandProcessor.java` 迁移到 `src/main/java/com/javasleuth/transport/BioTransportServer.java`，verify why.md#requirement-r1-concurrency-backpressure（本轮未落地）
 - [-] 1.3 引入 Netty 依赖并落地 `NettyTransportServer` 骨架：更新 `pom.xml`（引入 Netty 4.1.x，实施阶段锁定具体版本并做 CVE 检查），新增 `src/main/java/com/javasleuth/transport/NettyTransportServer.java`，verify why.md#requirement-r1-concurrency-backpressure（本轮未落地）
 - [-] 1.4 实现 framed/binary 协议编解码与握手：新增 `src/main/java/com/javasleuth/transport/netty/FrameCodec.java` 与 `src/main/java/com/javasleuth/transport/netty/HandshakeHandler.java`，并接入 `src/main/java/com/javasleuth/transport/NettyTransportServer.java`，verify why.md#requirement-r2-hmac-startup-self-contained（本轮未落地）
 - [-] 1.5 Launcher 客户端适配（保持 CLI 体验不变）：在 `src/main/java/com/javasleuth/launcher/SleuthLauncher.java` 中支持 `server.transport=netty` 的连接路径（协议保持兼容），verify why.md#requirement-r2-hmac-startup-self-contained（本轮未落地）

## 2. 并发与资源保护（有界队列 + 过载语义）
 - [√] 2.1 命令执行队列有界化：将 `src/main/java/com/javasleuth/command/CommandProcessor.java` 的 `LinkedBlockingQueue` 改为有界队列（容量来自新配置 `server.executor.queue.capacity`），并在 `src/main/java/com/javasleuth/config/ProductionConfig.java` + `src/main/resources/sleuth-default.properties` 增加默认值，verify why.md#requirement-r1-concurrency-backpressure
 - [√] 2.2 过载错误码与审计：当队列饱和/超过上限时，统一返回可解释错误（例如 `overloaded`），并在 `src/main/java/com/javasleuth/security/AuditLogger.java` 记录过载拒绝事件，verify why.md#requirement-r1-concurrency-backpressure

## 3. HMAC 默认行为自洽（减少启动断层）
 - [√] 3.1 loopback 下空 secret 自动生成：在 `src/main/java/com/javasleuth/config/ProductionConfig.java` 增加“当 `security.mode=hmac` 且 secret 为空时的启动策略（仅限 loopback）”，并在 `src/main/java/com/javasleuth/command/CommandProcessor.java` 启动前调用，verify why.md#requirement-r2-hmac-startup-self-contained
 - [√] 3.2 明确引导与报错：在 `src/main/java/com/javasleuth/launcher/SleuthLauncher.java` 输出更明确的安全引导信息（如何配置/如何获取会话信息/如何签名），verify why.md#requirement-r2-hmac-startup-self-contained

## 4. 会话隔离与断连自动清理（watch/trace/tt）
 - [-] 4.1 新增 `SessionResourceRegistry`：新增 `src/main/java/com/javasleuth/session/SessionResourceRegistry.java`，在 `src/main/java/com/javasleuth/command/CommandProcessor.java` 注册并在连接关闭时触发清理，verify why.md#requirement-r3-session-isolation-and-auto-cleanup（本轮发现现有 `ClientSessionRegistry/ClientSession.registerCleanup` 已覆盖断连清理语义，未重复引入新 Registry）
 - [-] 4.2 Watch 会话归属与自动注销：调整 `src/main/java/com/javasleuth/command/impl/WatchCommand.java` 注册键结构（必须包含 sessionId），并更新 `src/main/java/com/javasleuth/monitor/WatchInterceptor.java` 支持按 sessionId 清理，verify why.md#requirement-r3-session-isolation-and-auto-cleanup（保持现有 watchId 维度；已新增测试验证断连自动清理）
 - [-] 4.3 Trace 会话归属与自动注销：调整 `src/main/java/com/javasleuth/command/impl/TraceCommand.java` 注册键结构（必须包含 sessionId），并更新 `src/main/java/com/javasleuth/monitor/TraceInterceptor.java` 支持按 sessionId 清理，verify why.md#requirement-r3-session-isolation-and-auto-cleanup（保持现有 traceId 维度；已新增测试验证断连自动清理）
 - [-] 4.4 TT 会话归属与自动注销：调整 `src/main/java/com/javasleuth/command/impl/TtCommand.java` 注册键结构（必须包含 sessionId），并更新 `src/main/java/com/javasleuth/monitor/TtInterceptor.java` 支持按 sessionId 清理，verify why.md#requirement-r3-session-isolation-and-auto-cleanup（保持现有 ttId 维度；已新增测试验证断连自动清理）

## 5. 缓存语义统一（单一入口 + 明确粒度）
 - [√] 5.1 缓存决策收敛到 Pipeline：在 `src/main/java/com/javasleuth/command/CommandPipeline.java` 中统一实现 cacheable 语义（会话维度 cacheKey、TTL、可见性），并在 `src/main/java/com/javasleuth/util/PerformanceOptimizer.java` 补齐必要的命名空间/TTL API，verify why.md#requirement-r4-cache-single-source-of-truth
 - [√] 5.2 清理命令内部缓存（Dashboard/Memory）：移除 `src/main/java/com/javasleuth/command/impl/DashboardCommand.java` 与 `src/main/java/com/javasleuth/command/impl/MemoryCommand.java` 的内部缓存调用，改由 `CommandMeta.cacheable + CommandPipeline` 统一控制，verify why.md#requirement-r4-cache-single-source-of-truth

## 6. 重型命令治理（影响等级 + 二次确认 + 并发/速率限制）
 - [√] 6.1 命令元数据扩展：在 `src/main/java/com/javasleuth/command/CommandMeta.java` 增加“影响等级/资源级别”描述（例如 `impact=LOW|MEDIUM|HIGH`），并在 `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java` 为 `heapdump/jad/dump/redefine/retransform/reset/stop` 等命令标注，verify why.md#requirement-r5-heavy-command-governance
 - [√] 6.2 Pipeline 统一治理：在 `src/main/java/com/javasleuth/command/CommandPipeline.java` 统一接入 `DangerousCommandConfirmationManager`，对 HIGH 影响命令强制确认 + 并发限制（例如同一 JVM 同时仅允许 1 个 HIGH 命令执行），verify why.md#requirement-r5-heavy-command-governance

## 7. Security Check
 - [√] 7.1 执行安全自检：输入校验（长度/字符集/协议帧）、敏感信息处理（secret/password/token 不落日志）、权限控制（role 与 allowed.commands）、危险命令二次确认与审计全覆盖

## 8. Documentation / Knowledge Base Update
 - [√] 8.1 更新知识库架构文档：补充 transport 双栈（BIO/Netty）、会话资源治理与重型命令治理（`helloagents/wiki/arch.md`），并在 `helloagents/CHANGELOG.md` 记录变更
 - [√] 8.2 更新使用与运维文档：同步 `docs/ops/operations-runbook.md` 与 `docs/ops/production-deployment-guide.md` 的配置项与安全启动说明

## 9. Testing
 - [√] 9.1 并发/过载测试：新增 `src/test/java/com/javasleuth/command/CommandProcessorExecutorQueueTest.java` 覆盖队列有界与拒绝策略配置
 - [√] 9.2 会话断连清理测试：新增 `src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java` 覆盖 watch/trace/tt 注册后断连自动清理
 - [√] 9.3 HMAC 自洽启动测试：扩展 `src/test/java/com/javasleuth/command/CommandProcessorSecurityBoundaryTest.java` 覆盖 loopback 空 secret 自动生成与非 loopback 拒绝策略
 - [-] 9.4 Transport 双栈一致性测试：为 BIO/Netty 的握手与错误码建立最小集成测试（实施阶段确定测试策略与可行性）（本轮未落地 Netty/BIO 双栈，测试延期）
