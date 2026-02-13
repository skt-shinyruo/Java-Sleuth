# 模块：command（命令处理与服务端生命周期）

## 1. 模块职责（目标 JVM 内）

`command` 相关代码主要位于 `core/src/main/java/com/javasleuth/command/*`，运行在 **目标 JVM（agent-core）** 内，承担：

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

### 2.2 服务端生命周期拆分（去 God class）

为降低 `CommandProcessor` 的职责过载，引入 lifecycle 组件：

- `ServerBootstrapper`
  - 负责自举边界：读取配置、初始化安全要素、绑定端口等
- `ConnectionAcceptor`
  - 负责 accept 循环与连接级控制（如 maxConnections、过载拒绝）
- `ShutdownCoordinator`
  - 负责优雅/紧急关闭编排，保证幂等与资源释放顺序
  - 关闭编排会收口 `CommandPipeline`（含 `CommandExecutionEngine` 线程池），避免 detach 后残留后台执行器
- `CommandProcessor`
  - 作为 facade：装配上述组件 + 现有的 `CommandClientHandler`，对外保持稳定入口
  - 支持“注入式构造方法”，将单例获取收敛到 composition root（如 `SleuthAgentCore`），降低依赖隐式化

收益：

- 生命周期逻辑可单测（连接上限、拒绝策略、shutdown 幂等）
- 后续演进可替换局部实现（不同 accept 策略/协议 handler），减少回归面

### 2.3 协议集成（server side）

`core/src/main/java/com/javasleuth/command/server/protocol/*`：

- `HandshakeNegotiator`：握手/协商（含安全相关协商点）
- `FramedClientCommandHandler`：framed 请求处理（协议状态机与执行边界）
- `BinaryClientProtocolHandler`：二进制协议处理
- `CommandRequestExecutor`：把请求映射到命令执行
- `CommandReplyChannel` / `FramedReplyChannel`：回包通道抽象

补充（SSOT）：`HELLO/CONFIG/SIG` 这类行级 `k=v` 解析统一由 `foundation/src/main/java/com/javasleuth/command/protocol/KvLineCodec.java` 提供（key 归一化使用 `toLowerCase(Locale.ROOT)`，并由 `KvLineCodecTest` 覆盖关键边界），避免 launcher/server/security 各自维护解析规则导致漂移。

## 3. 约束与注意事项

- **安全边界**：服务端入口处必须先完成握手/鉴权/确认流程，再允许进入高危命令执行
- **资源治理**：accept 循环需要显式限流/拒绝策略，避免目标 JVM 被诊断流量拖垮
- **detach/reattach 语义**：shutdown 编排需要覆盖“网络/线程池/安全缓存/插件 classloader”等关键资源，避免同 JVM 二次 attach 时状态残留
- **可测试性优先**：尽量把“线程/IO/生命周期”与“纯逻辑”分离，降低 flaky 风险
