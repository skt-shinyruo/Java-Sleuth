# 模块：command（命令处理与服务端生命周期）

## 1. 模块职责（目标 JVM 内）

`command` 相关代码主要位于 `core/src/main/java/com/javasleuth/core/command/*`，运行在 **目标 JVM（agent-core）** 内，承担：

- 命令注册、解析与分发（registry/parser/provider）
- 命令执行管线（pipeline/job manager）
- 命令服务端生命周期（bind/accept/handshake/执行/关闭）
- 与协议层 handler 的集成（framed/binary）

## 2. 核心类型（概览）

### 2.1 命令抽象

- `Command` / `StreamCommand`：命令接口
- `CommandArgs`：参数解析后的结构化对象
- `CommandContext`：一次请求的上下文（身份/权限/连接信息等）
- `CommandRegistry` / `CommandProvider`：命令注册与聚合
  - Registry 职责收敛：`CommandRegistry` 仅负责“聚合注册 + 冲突策略 + 关闭编排”
    - 冲突策略显式类型化：`CommandConflictStrategy`（prefer-builtin/prefer-plugin/fail）
    - shutdown 会 best-effort 关闭实现了 `AutoCloseable` 的命令，并在最后关闭插件 classloader（若存在）
  - Provider 发现与插件供应链校验剥离：
    - provider 发现（ServiceLoader/插件目录）与 allowlist+sha256 校验由 `com.javasleuth.core.command.plugin.CommandProviderLoader` 负责
    - composition root（当前为 `CommandProcessorFactory` / container 入口）负责构建 provider 列表并交给 registry 注册
  - 约束：插件命令必须提供 `CommandMeta`（不提供则拒绝注册），避免安全层与命令层的 SSOT 漂移

### 2.2 服务端生命周期拆分（去 God class）

为降低 `CommandProcessor` 的职责过载，引入 lifecycle 组件：

- `ServerBootstrapper`
  - 负责自举边界：读取配置、初始化安全要素、绑定端口等
  - JobManager 配置走注入：由组合根（`SleuthAgentRuntime`/`CommandProcessorFactory`）提供 `JobManager` 实例并传入 `configureJobManager(jobManager, cfg)`，避免在 bootstrapper 内部隐式调用 `getInstance()`
- `ConnectionAcceptor`
  - 负责 accept 循环与连接级控制（如 maxConnections、过载拒绝）
- `ShutdownCoordinator`
  - 负责优雅/紧急关闭编排，保证幂等与资源释放顺序
  - 关闭编排会收口 `CommandPipeline`（含 `CommandExecutionEngine` 线程池），避免 detach 后残留后台执行器
- `CommandPipeline`
  - 注入优先：构造器 strict non-null，必须显式注入 `DangerousCommandConfirmationManager`，避免在构造器内部隐式调用 `getInstance()`
- `CommandProcessor`
  - 作为 facade：对外保持稳定入口（`start/shutdown/restart/...`），内部只做编排与状态持有
  - 依赖装配与全局副作用由 `CommandProcessorFactory` / composition root（如 `SleuthAgentCore`）集中处理，避免门面类继续膨胀
  - 通过 `CommandProcessorComponents` 聚合依赖，降低构造器参数与字段扇出

收益：

- 生命周期逻辑可单测（连接上限、拒绝策略、shutdown 幂等）
- 后续演进可替换局部实现（不同 accept 策略/协议 handler），减少回归面

### 2.3 协议集成（server side）

`core/src/main/java/com/javasleuth/core/command/server/protocol/*`：

- `HandshakeNegotiator`：握手/协商（含安全相关协商点）
- `FramedClientCommandHandler`：framed 请求处理（协议状态机与执行边界）
- `BinaryClientProtocolHandler`：二进制协议处理
- `CommandRequestExecutor`：把请求映射到命令执行
- `CommandReplyChannel` / `FramedReplyChannel`：回包通道抽象

补充：会话映射边界

- `ClientSessionIndex`：封装 `clientId -> sessionId` 映射，替代在多个组件间直接共享 `ConcurrentHashMap`，显式化会话索引边界并降低状态穿透

补充（SSOT）：`HELLO/CONFIG/SIG` 这类行级 `k=v` 解析统一由 `foundation/src/main/java/com/javasleuth/foundation/command/protocol/KvLineCodec.java` 提供（key 归一化使用 `toLowerCase(Locale.ROOT)`，并由 `KvLineCodecTest` 覆盖关键边界），避免 launcher/server/security 各自维护解析规则导致漂移。

## 3. 约束与注意事项

- **安全边界**：服务端入口处必须先完成握手/鉴权/确认流程，再允许进入高危命令执行
- **资源治理**：accept 循环需要显式限流/拒绝策略，避免目标 JVM 被诊断流量拖垮
- **detach/reattach 语义**：shutdown 编排需要覆盖“网络/线程池/安全缓存/插件 classloader”等关键资源，避免同 JVM 二次 attach 时状态残留
  - 补充：包含命令级后台资源（如 profiler scheduler）与 vmtool track 这类“跨调用会话状态”（session registry + bootstrap interceptor cache）
  - 补充：隔离 `URLClassLoader` 作为一次 attach 的生命周期边界；shutdown 后由 `CoreClassLoaderRegistry.onCoreShutdown(...)` best-effort 释放/关闭该 ClassLoader，降低 JAR 锁与 static 状态残留风险
- **可测试性优先**：尽量把“线程/IO/生命周期”与“纯逻辑”分离，降低 flaky 风险
