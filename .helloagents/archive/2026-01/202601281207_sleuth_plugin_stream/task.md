# Task List: 命令插件化与流式诊断（细化版）

Directory: `helloagents/plan/202601281207_sleuth_plugin_stream/`

---

## 1. 协议分帧与兼容模式
- [√] 1.1 新增分帧模型与编解码在 `src/main/java/com/javasleuth/command/protocol/Frame.java`, `src/main/java/com/javasleuth/command/protocol/FrameCodec.java`, verify why.md#requirement-framed--streaming-protocol-scenario-stream-watchtrace-events
- [√] 1.2 CommandProcessor 支持 framed/legacy 双协议在 `src/main/java/com/javasleuth/command/CommandProcessor.java`, verify why.md#requirement-framed--streaming-protocol-scenario-stream-watchtrace-events
- [√] 1.3 Launcher 协议协商与帧解析在 `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`, verify why.md#requirement-framed--streaming-protocol-scenario-stream-watchtrace-events
- [√] 1.4 新增协议配置项在 `src/main/java/com/javasleuth/config/ProductionConfig.java`, `src/main/resources/sleuth-default.properties`, `config-templates/production-sleuth.properties`, verify why.md#requirement-framed--streaming-protocol-scenario-stream-watchtrace-events

## 2. 插件化命令加载
- [√] 2.1 定义 CommandProvider SPI 与内置 Provider 在 `src/main/java/com/javasleuth/command/CommandProvider.java`, `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`, verify why.md#requirement-pluginized-command-system-scenario-load-providers-at-startup
- [√] 2.2 新增 CommandRegistry（SPI + 插件目录扫描 + 冲突策略）在 `src/main/java/com/javasleuth/command/CommandRegistry.java`, verify why.md#requirement-pluginized-command-system-scenario-load-providers-at-startup
- [√] 2.3 CommandProcessor 迁移到 CommandRegistry 在 `src/main/java/com/javasleuth/command/CommandProcessor.java`, verify why.md#requirement-pluginized-command-system-scenario-load-providers-at-startup

## 3. 命令执行管线与权限接入
- [√] 3.1 引入 CommandPipeline 与元数据在 `src/main/java/com/javasleuth/command/CommandPipeline.java`, `src/main/java/com/javasleuth/command/CommandMeta.java`, verify why.md#requirement-unified-execution-pipeline-scenario-execute-with-pipeline
- [√] 3.2 接入 Authentication/Authorization 在 `src/main/java/com/javasleuth/security/AuthenticationManager.java`, `src/main/java/com/javasleuth/security/AuthorizationManager.java`, `src/main/java/com/javasleuth/command/CommandProcessor.java`, verify why.md#requirement-session-auth--authorization-scenario-authenticate-and-enforce
- [√] 3.3 新增 auth/login 命令在 `src/main/java/com/javasleuth/command/impl/AuthCommand.java`, `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`, verify why.md#requirement-session-auth--authorization-scenario-authenticate-and-enforce

## 4. Enhancer 叠加与会话隔离
- [√] 4.1 改造 Enhancer 存储为“类名 -> 列表”在 `src/main/java/com/javasleuth/enhancement/SleuthClassFileTransformer.java`, verify why.md#requirement-enhancer-isolation--backpressure-scenario-concurrent-watches-on-same-class
- [√] 4.2 新增 EnhancerChain 组合器在 `src/main/java/com/javasleuth/enhancement/EnhancerChain.java`, verify why.md#requirement-enhancer-isolation--backpressure-scenario-concurrent-watches-on-same-class
- [√] 4.3 watch/trace 以会话 ID 精确移除 enhancer 在 `src/main/java/com/javasleuth/command/impl/WatchCommand.java`, `src/main/java/com/javasleuth/command/impl/TraceCommand.java`, verify why.md#requirement-enhancer-isolation--backpressure-scenario-concurrent-watches-on-same-class

## 5. 背压与采样策略
- [√] 5.1 watch 队列上限与丢弃策略在 `src/main/java/com/javasleuth/monitor/WatchInterceptor.java`, `src/main/java/com/javasleuth/config/ProductionConfig.java`, verify why.md#requirement-enhancer-isolation--backpressure-scenario-high-frequency-event-burst
- [√] 5.2 trace 队列上限与采样策略在 `src/main/java/com/javasleuth/monitor/TraceInterceptor.java`, `src/main/java/com/javasleuth/config/ProductionConfig.java`, verify why.md#requirement-enhancer-isolation--backpressure-scenario-high-frequency-event-burst

## 6. Security Check
- [-] 6.1 Execute security check (per G9: input validation, sensitive info handling, permission control, EHRB risk avoidance)

## 7. Documentation Update
- [√] 7.1 Update knowledge base files under `helloagents/wiki/` for modules command/security/launcher/enhancement/monitor

## 8. Testing
- [√] 8.1 Add protocol/registry unit tests in `src/test/java/com/javasleuth/command/CommandProcessorTest.java`: 分帧解码、插件加载、兼容模式、权限校验
