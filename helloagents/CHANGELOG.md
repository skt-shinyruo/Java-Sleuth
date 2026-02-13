# Changelog

This file records all important project changes.
Format based on [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/),
version numbers follow [Semantic Versioning](https://semver.org/lang/zh-CN/).

## [Unreleased]

### Added
- 分层边界守护：新增 `foundation` Maven 模块（承载 util/config/security/data），以编译期模块边界阻断低层反向依赖高层
- 插件化命令注册与分帧协议基础设施
- Docker 演示镜像：内置 `EnhancedTestApplication`，支持 `docker exec -it` 纯交互 attach 与命令演示
- auth 命令与会话角色绑定
- HELLO/CONFIG 握手协商与 binary 严格二进制帧协议
- security.mode=off|hmac（默认 off）与可选 HMAC+nonce 请求签名/基础防重放
- 危险命令二次确认（默认关闭，可选开启）：`security.dangerous.confirm.*` + `--confirm <token>`（一次性、短 TTL）
- Launcher 支持 `--insecure`（需交互确认 `I UNDERSTAND`）用于本机单次排障开启 `security.mode=off`
- server.bind.address 默认 127.0.0.1（降低默认口令/明文传输暴露面）
- ClientSessionRegistry/ClientSession：监控类命令注册断连清理动作，连接写失败时快速终止并回收增强/队列
- 协议/插件/安全相关指标：handshake、binary upgrade、plugin load、security_verify
- watch/trace 事件丢弃/采样计数与 status 输出
- UTF-8 行编解码（Utf8LineCodec）与 `protocol.text.max.line.bytes` 单行字节数上限
- 轻量日志封装 `SleuthLogger`（通过 `logging.level` 控制增强日志等级）
- 安全默认策略开关：`security.anonymous.viewer`（默认 true）
- Arthas-like 核心能力（简化版）：watch/trace/monitor/tt/jobs/reset 及管理命令集
- `jobs`（JobManager + list/tail/stop）与 `--bg` 后台执行（watch/trace/monitor/tt）
- `tt`（TT-lite）：record/list/detail（不含 replay）
- watch/trace 增强：`--expr`（字段选择）+ `--condition`（受控条件过滤，支持 cost 单位）
- trace 聚合输出：按“单次调用”成树输出（TraceAggregator）
- `monitor` 重写为周期统计输出（可后台 jobs）
- `dump`（导出 class 资源字节码）、`getstatic`（静态字段只读查看）、`logger`（JUL list/set）
- `vmtool`（lite）：实例追踪（track/instances/inspect）+ 受控方法调用（invoke/invoke-static，子命令级二次确认）+ histogram（HotSpot best-effort）
- `session`/`perm`/`version`/`stop`/`reset` 等管理命令
- `vmoption`（HotSpotDiagnosticMXBean）支持 list/get/set（set 需 admin）
- `thread -n <N> -i <ms>`：按 CPU delta 输出 top 线程
- 单测补齐：wildcard/ring buffer/condition/trace 聚合/内存编译产物
- `stack` 方法触发调用栈追踪（Arthas stack 简化版，支持 `--bg/--depth`），并在 status 输出 stack 事件统计
- `tt replay <recordId>`：生成复现代码模板（lite，不在目标 JVM 执行）
- 插桩健壮性回归：Watch/Tt 增强器的返回值/异常语义与字节码校验（VerifyError）测试
- `JarLocator`：启动/发布不再依赖硬编码 jar 名称，支持 `-Dsleuth.agent.jar` / `SLEUTH_AGENT_JAR` 覆盖
- 安全自举配置：`security.bootstrap.hmac.*`、`security.hmac.session.role`（当 `security.mode=hmac` 时可选下发/补齐 secret）
- 口令认证开关与配置：`security.auth.password.enabled` + `security.auth.*.password`（支持 `SLEUTH_AUTH_*_PASSWORD`）
- 插件加固配置：`plugins.enabled`（默认关闭）+ `plugins.allowlist.sha256`
- trace 调用级采样覆盖：`trace --sample <rate>`（支持按 trace 会话覆盖采样率）
- 审计输出可控：`logging.audit.console.enabled`、`logging.audit.file.path`、`logging.security.file.path`（默认落盘到 tmp 并带 pid 后缀）
- monitor 独立采样 key：`monitoring.monitor.sample.rate`
- 关键边界单测补齐：非回环 bind + `security.mode=off` 拒绝启动、`security.mode=hmac` 但 secret 为空拒绝启动、协议上限异常路径
- 多 ClassLoader 目标选择器 `LoadedClassResolver`：输出候选与 loaderId，支持 `--loader` 精确选类
- 插桩失败冷却可重试：`enhancement.failure.*`（避免失败后静默移除 enhancer）
- jobs 并发硬上限与队列上限：`jobs.max.running` / `jobs.queue.capacity`
- classpath ServiceLoader 插件开关：`plugins.serviceloader.enabled`
- `config save --include-runtime`：可选持久化 runtime overrides
- `mc --encoding`：源码读取默认 UTF-8 并支持显式编码
- `docs/tutorial/` 教学文档目录：补充运行时 Attach、Agent 生命周期、Instrumentation/Transformer 与 retransform 生效机制说明
- 教学文档补充：命令触发插桩与回滚链路（watch/trace/reset/stop）

### Changed
- 关键依赖环拆除：`CommandMeta` 下沉到 `com.javasleuth.security`（SSOT），`security` 不再依赖 `command`；`SleuthLogger` 通过 `SleuthLogContext` 注入上下文，避免 `util -> command`；`stop` 通过注入的 shutdown hook 触发 Agent shutdown，避免 `command -> agent`
- 线程与生命周期治理：`AuthenticationManager` 会话清理任务改为可 shutdown 的调度器并纳入关闭编排；`JobManager` 支持 shutdown 并在后台 job 传播/清理上下文；`AuditLogger`/`PerformanceOptimizer` 支持 detach→re-attach 场景重启
- 生命周期闭环补齐：`CommandExecutionEngine`/`CommandPipeline` 增加 `shutdown()` 并由 `ShutdownCoordinator` 统一收口；`AuthorizationManager`/`RequestSecurityManager`/`DangerousCommandConfirmationManager` 增加 `shutdownInstance()` 清理缓存，降低同 JVM detach→re-attach 的状态残留风险；关键线程池进一步统一到 `SleuthThreadFactory`
- 命令/客户端边界进一步显式化：`ProtocolClient` 支持注入 `RequestSecurityManager`（默认行为保持不变），`CommandRegistry.shutdown()` best-effort 关闭实现了 `AutoCloseable` 的命令（用于治理命令级后台线程/资源）
- 安全组件可注入与实例级 shutdown：`AuthorizationManager`/`RequestSecurityManager`/`DangerousCommandConfirmationManager` 增加 instance `shutdown()`；`ShutdownCoordinator` 优先 shutdown 注入实例并 best-effort 清理全局单例；`vmtool/auth/session` 命令改为依赖注入（默认构造保留兼容）
- Bootstrap 边界收敛：新增 `bootstrap` Maven 模块（`java-sleuth-bootstrap`）承载 spy/bridge（`monitor`/`data`/值快照工具/JarLocator/AgentArgsApplier），`agent` 仅依赖该模块并 append 到 bootstrap；`foundation` 的 config/security/protocol 等能力不再被提升为 bootstrap 可见；jar 定位与 agentArgs 落地规则统一为 SSOT
- 移除 ArchUnit 架构守护测试与依赖（按团队偏好，避免测试代码承载分层守护逻辑）
- 配置层去中心化：引入 `ConfigView`/`MutableConfig`/`ConfigOrigin` 与 `RuntimeConfigStore`（运行时覆写审计），`ProductionConfig` 拆职责并退化为 Facade；部分命令构造改为注入 `ConfigView`，减少散落的 `ProductionConfig.getInstance()` 调用点
- 示例/测试应用从 main 源集迁移到 `examples/`，发布 jar/fat-jar 不再包含 `com.javasleuth.test.*`（Docker demo 与脚本改为运行 examples 编译产物）
- Maven 多模块化：根工程改为 parent/aggregator（`packaging=pom`），主产物迁移到 `core/` 模块，示例应用作为 `examples/` 模块独立构建；脚本/Docker/文档同步更新
- Launcher/Agent 产物与依赖隔离升级：拆分为 `launcher/`（CLI）+ `agent/`（bootstrap agent，appendToBootstrap，保持 JDK-only）+ `core/`（`java-sleuth-agent-core`，隔离 ClassLoader 加载，包含 ASM/Jackson/CFR/RE2J...）；`JarLocator` 按 Manifest 标记 `Sleuth-Agent-Bootstrap` / `Sleuth-Agent-Core` 自动定位产物；`com.javasleuth.monitor`/`com.javasleuth.data`/值快照工具等桥接类下沉到 `bootstrap` 并保持无第三方依赖（仅 JDK）；`com.javasleuth.command.protocol` 保持在 `foundation`（JDK-only）；脚本/Docker/文档同步更新
- CommandProcessor 改为注册表 + 统一执行管线
- CommandPipeline 执行链路显式化：引入 Step/Interceptor Chain（precheck/sync/stream），降低巨型类耦合并提升可测性
- CommandProcessor 拆分出 CommandClientHandler（framed/binary 协议处理），CommandProcessor 聚焦监听/生命周期
- CommandProcessor 门面纯化：引入 `CommandProcessorFactory` + `CommandProcessorComponents` 作为装配边界；会话映射由 `ClientSessionIndex` 封装，减少共享状态穿透与横切关注点耦合
- CommandClientHandler 进一步按协议拆分：framed/binary handlers + 共享 CommandRequestExecutor，握手协商抽取为 HandshakeNegotiator
- Launcher 支持 framed/stream 协议与端口配置读取
- Enhancer 支持链式叠加与按会话移除
- CommandProcessor 支持 bind address + handshake 协商并可升级 binary 通道
- Launcher 支持 handshake 协商与 binary 通道；在 security.mode=hmac 时自动封装 SIG 请求
- 移除 legacy 文本协议，统一使用 framed/binary（保持 handshake 协商，提升长输出/流式命令稳定性）
- 授权策略 SSOT：以 CommandMeta 为唯一权限来源，AuthorizationManager 不再维护命令名特判/映射；插件命令必须提供 meta
- 项目根目录结构整理：文档集中到 docs/，脚本归档到 scripts/
- docs/ 文档中文化：用户/开发/运维文档说明文字统一为简体中文（保留命令/配置示例可复制）
- 安全默认调整：默认不启用认证/签名校验（`security.mode=off`），并默认关闭 RBAC（`security.authorization.enabled=false`）
- sysprop 写入改为显式子命令 `sysprop set <key> <value>`（并要求更高权限）
- 插桩过滤策略放开常见代理类（例如 Spring/CGLIB `$$EnhancerBySpringCGLIB$$`），transform 逐次日志默认降噪（DEBUG 才输出）
- 性能维护策略：默认不再定时触发 `System.gc()`（由 `performance.maintenance.force_gc` 控制）
- Config/Sysprop 等命令输出对敏感值自动脱敏（避免控制台/日志泄露 secret）
- 配置治理：引入强类型配置模型 `SleuthConfig`/`ServerConfig`/`ProtocolConfig`/`SecurityConfig` 与集中解析器 `SleuthConfigParser`；连接/会话边界（CommandClientHandler/HandshakeNegotiator/Launcher）统一解析默认与派生上限，`DefaultConfigFallback` 默认值收敛为 `SleuthDefaults`；补齐默认一致性与派生默认回归测试，降低“字符串 key + 多处默认值”漂移风险
- Watch/Trace/Monitor/TT 方法匹配支持 `*` 通配符（例如 `execute*`），并修复异常路径退出事件捕获
- WatchResult 输出改为使用安全格式化（SleuthValueFormatter：限深/限长/脱敏）
- Monitor/VmOption 命令接口调整为更贴近 Arthas 的简化子命令模型
- 默认 trace 采样率调整为更保守值（`monitoring.trace.sample.rate=0.1`），降低高 QPS 场景误用风险
- Launcher/脚本启动方式去版本/目录硬编码：支持任意 cwd 启动与通配符定位 `*-jar-with-dependencies.jar`
- 审计日志默认不再刷屏控制台（需显式开启 `logging.audit.console.enabled=true`）
- fat-jar Manifest 补齐 `Main-Class`，支持 `java -jar` 直接启动 Launcher（不破坏 Agent 能力）
- 默认配置与实现对齐：移除无效 `production.*`，补齐 `jobs.*`/`protocol.frame.max.payload` 等关键默认项并同步文档
- 连接侧背压与可配置上限：`server.executor.queue.capacity`（连接处理线程池队列有界化，过载时拒绝新连接并返回明确错误）
- 命令执行侧背压与可配置上限：`performance.command.executor.core/max/queue.capacity`（替代 `Executors.newCachedThreadPool`，避免线程膨胀与无限排队）
- loopback 自洽启动：当 `security.mode=hmac` 且 `security.hmac.secret` 为空时，回环绑定下可自动生成临时 secret（明文 secret 仅在交互控制台打印，`security.hmac.secret.autogen.*` 控制）
- 高影响命令治理：`CommandMeta.impact=LOW|MEDIUM|HIGH` + `security.impact.high.*`（二次确认 + 并发限制，默认同一时刻仅允许 1 条高影响命令执行）
- 流式命令执行链路统一：StreamCommand 走 `CommandPipeline` 的 executor/timeout/输出治理（sanitize/truncate），减少连接线程被长时间业务逻辑占用
- `stack`/`tt` 实现子模块化：解析/会话/执行/格式化拆分到 `com.javasleuth.command.impl.stack.*` 与 `com.javasleuth.command.impl.tt.*`，降低巨型文件风险

### Fixed
- watch/trace 队列增加背压与采样
- TraceInterceptor ThreadLocal 在 map 为空时执行 remove，降低线程池场景潜在残留与固定开销
- CommandParser 反斜杠转义字符解析修复
- 命令参数解析与异常处理加固：统一数值解析/范围校验/错误码（`E_ARGS_*`），修复吞异常黑洞并补齐 DEBUG 日志；关键链路大小写归一化改为 Locale-independent（避免默认 Locale 影响命令识别）
- PerformanceOptimizer/MemoryOptimizer 编译问题修复（静态 API/缓存清理/ MBean 接口）
- Launcher 进程列表过滤后序号不一致导致的误选问题
- Launcher 连接地址不再写死 localhost（按 bind 地址/协商信息解析，0.0.0.0/:: 回退 loopback）
- 传输层消除 BufferedReader/PrintWriter 与 Data*Stream 混用导致的升级不稳定风险
- detach→re-attach 残留治理补齐：Agent shutdown 路径显式清理 vmtool track sessions 与 bootstrap interceptor 缓存；Profiler 命令支持 close 并避免定时线程在 shutdown 后残留
- AuthenticationManager 锁定窗口与客户端标识解析修复（支持 /ip:port、IPv6、unknown）
- 审计日志脱敏加强：auth/config/sysprop 等命令参数与 sessionId 不再以明文写入
- server.max.connections 与 performance.command.timeout 配置落地生效
- 缓存语义一致性：移除命令内部“自建缓存”，以 `CommandMeta.cacheable` + `CommandPipeline` 作为缓存唯一入口（避免全局缓存与会话缓存混用导致实时性/边界误判）
- MemoryJavaCompiler 编译产物未落入 compiledClasses 的问题（通过 OutputStream.close 挂钩收集字节码）
- HMAC 模式下新连接会话自举：避免“匿名 viewer 关闭 + 口令认证关闭”导致命令不可用
- TraceInterceptor 采样改为调用级一致：entry/exit/subcall 配对与 depth 稳定
- 插件目录加载默认关闭，并在 shutdown 释放 URLClassLoader（降低 Windows JAR 锁定风险）
- AuthenticationManager 移除硬编码 demo 口令，改为显式配置/环境变量
- AuditLogger 支持可配置落盘路径并减少对目标 JVM stdout/stderr 的污染
- Java 8 运行时兼容性：移除 Java 11 `String.repeat` 与 Java 9 `Field.canAccess` 依赖，并在 `mvn verify` 阶段增加 Java 8 API 校验（避免“JDK 11 编译通过、Java 8 运行炸”）
- jad 反编译可用性：将获取到的 `.class` bytecode 可靠传入 CFR（临时文件方式），避免“传了类名但无输入”导致的空输出
- session 泄露/串线：`session` 命令默认不缓存、输出 token 默认脱敏（`--show-token` 显式开启）；命令缓存 key 增加 clientId 维度防止跨客户端串线
- wildcard/regex 稳定性：通配符匹配改为转义正则元字符；`sm -E` 采用 RE2/J 避免 ReDoS 并提供更友好的语法错误提示
- watch/tt 资源风险：采集阶段引入“值快照”（限深/限长），避免把参数/返回值/异常对象强引用驻留到队列/环形缓冲导致内存压力
- trace 语义：同一类内“可被 trace 的方法调用”不再产生重复 SUB_METHOD_CALL；采样以根调用为单位并向子调用继承，降低碎片化树
- stdout/stderr 污染：`logging.performance.enabled` 默认关闭（可配置开启）
- 配置严格性修复：禁用 legacy 配置键校验（`protocol.handshake.enabled`、`protocol.text.end.marker.enabled`）在加载阶段可控且默认严格拒绝（可通过 `-Dsleuth.config.forbidden.keys.policy=off|warn|strict` 调整）
- 日志/输出收敛：业务逻辑中零散的 `System.out/err` 统一改用 `SleuthLogger`，并补齐上下文字段（clientId/sessionId/connId/command）与 `logging.console.enabled` 开关，降低线上聚合成本与单测噪声
- 异常兜底与最小披露：新增 `CommandErrorMapper` + `errorId` 关联字段；命令失败不再把堆栈/内部细节写入用户输出或协议 ERR，仅在 `SleuthLogger` 诊断日志中保留堆栈
- `tt replay` 模板输出移除 TODO 占位，改为明确限制说明与更可复制的 Java 模板
- `profiler` 文案澄清当前实现不依赖 async-profiler（避免误导）
- 多 ClassLoader 场景稳定性：watch/trace/redefine 支持 `--loader` 精确选类，session 回滚绑定同一 `Class<?>`，避免同名类选错/回滚错
- ASM `COMPUTE_FRAMES` 可靠性：使用 loader-aware ClassWriter，失败不再移除 enhancer，改为冷却+可重试并暴露可观测指标
- 后台任务线程模型：`--bg` 不再“每 job 新线程”，改为有界线程池并提供背压与明确拒绝提示
- 插件默认禁用语义一致：默认不加载目标进程 classpath 上的 ServiceLoader provider（需显式开启）
- 文件/编码治理一致：`mc` 源码读取默认 UTF-8；`redefine` 文件读取统一走 `SecurityValidator.canReadFile` 校验
- 移除 legacy 文本协议：统一使用 framed/binary，消除逐行回包缺少显式边界导致的多行输出错位风险；输出截断提示不再主动注入换行

## [1.0.0] - 2026-01-28

### Added
- 初始化知识库文档结构与项目概览

## 2026-02-08 强制新协议（无旧兼容）

- Breaking: 握手为强制流程；不再支持未握手直发命令。
- Breaking: 移除/拒绝旧配置键 `protocol.handshake.enabled`、`protocol.text.end.marker.enabled`。
- Breaking: `security.mode=hmac` 仅接受单一 `SIG` 格式（禁用 `v` 字段），且必须携带并绑定 `sid`。
- Breaking: `protocol.mode` 非法值不再自动归一化，启动直接失败。
## 2026-02-12

- launcher：将 `SleuthLauncher` 收敛为组合根（composition root），引入可插拔运行模式（interactive/headless），解耦 JVM 选择/Attach/协议客户端/交互 UI。
- command：将 `CommandProcessor` 的生命周期职责拆分为 `ServerBootstrapper`、`ConnectionAcceptor`、`ShutdownCoordinator`，降低 God class 风险并增强可测试性。
- protocol/security：握手与 `SIG` 的 KV 行解析统一下沉到 `foundation` 的 `KvLineCodec`，消除重复实现与服务端对握手解析器的非必要复用，降低漂移风险。
- protocol/security：移除握手与 `SIG` 的 `parseHandshakeKv/parseKv` 薄封装，调用点直接使用 `KvLineCodec`，进一步减少潜在漂移点。
- protocol/security：`KvLineCodec` 的 key 归一化改为 `toLowerCase(Locale.ROOT)` 并补充 `KvLineCodecTest`，避免非英语 Locale 下的解析命中异常。
- agent：通过 bootstrap → core 的隔离加载策略，降低 ASM/Jackson/CFR/JLine 等第三方依赖与业务依赖碰撞概率。
- 验证：`mvn test`、`mvn -DskipTests package` 通过。

## 2026-02-13

- security：新增最小签名接口 `CommandSigner`，并由 `RequestSecurityManager` 实现（仅覆盖客户端签名能力，服务端校验逻辑不变）。
- security：`RequestSecurityManager` / `AuthorizationManager` 增加显式构造注入路径（兼容保留 `getInstance()`）。
- launcher：`ProtocolClient.connect` 新增接收 `CommandSigner` 的重载；兼容保留 `RequestSecurityManager` 重载与默认 connect 行为。
- command：引入 `CommandProcessorFactory` / `CommandProcessorComponents` / `ClientSessionIndex`，将装配与会话映射边界显式化，降低隐式依赖与演进耦合。
