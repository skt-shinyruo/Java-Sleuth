# Task List: Launcher/CommandProcessor 去 God class（可插拔运行模式）

Directory: `helloagents/plan/202602112307_launcher_commandprocessor_modularization/`

---

## 1. launcher：运行模式与装配入口瘦身
- [√] 1.1 新增 `launcher/src/main/java/com/javasleuth/launcher/cli/LauncherArgs.java`（解析 `--pid/--cmd/--script/--fail-fast/--insecure` 等），并在 `launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java` 仅保留装配/分发逻辑，verify why.md#requirement-launcher-组件化与运行模式-scenario-默认交互模式保持兼容
- [√] 1.2 新增 `launcher/src/main/java/com/javasleuth/launcher/cli/LaunchMode.java`（interactive/headless），并在 `launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java` 根据 args 选择 runner，verify why.md#requirement-launcher-组件化与运行模式-scenario-默认交互模式保持兼容
- [√] 1.3 抽取 JVM 发现/过滤：`launcher/src/main/java/com/javasleuth/launcher/jvm/JvmDiscovery.java` + `AttachJvmDiscovery`，并保留“过滤 Sleuth 自身/展示序号一致”的语义，verify why.md#requirement-launcher-组件化与运行模式-scenario-默认交互模式保持兼容
- [√] 1.4 抽取 JVM 选择 UI：`launcher/src/main/java/com/javasleuth/launcher/jvm/JvmSelector.java` + `JlineJvmSelector`，并让 `SleuthLauncher` 不再直接读写终端输入，verify why.md#requirement-launcher-组件化与运行模式-scenario-默认交互模式保持兼容

## 2. launcher：Attach 与 agentArgs 构建隔离
- [√] 2.1 新增 `launcher/src/main/java/com/javasleuth/launcher/attach/AgentArgsBuilder.java`（负责从 `ProductionConfig` 与 CLI 组合出 agentArgs，包含 `coreJar=` 注入），verify why.md#requirement-launcher-组件化与运行模式-scenario-默认交互模式保持兼容
- [√] 2.2 新增 `launcher/src/main/java/com/javasleuth/launcher/attach/AgentAttacher.java`（封装 `VirtualMachine.attach/loadAgent/detach`），并在错误路径输出清晰提示，verify why.md#requirement-launcher-组件化与运行模式-scenario-默认交互模式保持兼容
- [√] 2.3 为 `AgentAttacher` 引入可替换接口（例如 `AttachApi`）以便单测覆盖“不实际 attach”场景，verify why.md#requirement-可测试性与回归基座-scenario-单测覆盖核心解析与编排

## 3. launcher：ProtocolClient 与交互/批处理 runner
- [√] 3.1 新增 `launcher/src/main/java/com/javasleuth/launcher/client/ProtocolClient.java` 与实现（framed/binary），将握手协商、binary upgrade、帧读写从 `SleuthLauncher` 中剥离，verify why.md#requirement-协议客户端解耦-scenario-握手协商与协议升级
- [√] 3.2 新增 `launcher/src/main/java/com/javasleuth/launcher/client/HandshakeClient.java`（生成 HELLO、解析 CONFIG、协商 connId/maxpayload/streaming），并补单测，verify why.md#requirement-协议客户端解耦-scenario-握手协商与协议升级
- [√] 3.3 新增 `launcher/src/main/java/com/javasleuth/launcher/shell/InteractiveShell.java`（基于 JLine，只负责读取命令与展示输出），verify why.md#requirement-launcher-组件化与运行模式-scenario-默认交互模式保持兼容
- [√] 3.4 新增 `launcher/src/main/java/com/javasleuth/launcher/shell/HeadlessRunner.java`（支持 `--cmd` 与 `--script`，并实现 `--fail-fast`），verify why.md#requirement-launcher-组件化与运行模式-scenario-headless-脚本批量执行
- [√] 3.5 抽取 streaming 策略：`launcher/src/main/java/com/javasleuth/launcher/shell/StreamPolicy.java`（决定哪些命令走 STREAM/stream=true），并补单测，verify why.md#requirement-协议客户端解耦-scenario-流式命令输出与帧读取

## 4. core：CommandProcessor 生命周期组件化（继续去 God class）
- [√] 4.1 新增 `core/src/main/java/com/javasleuth/command/server/ServerBootstrapper.java`：封装 JobManager 配置、SleuthLogger config provider 绑定、HMAC secret 自举等启动准备逻辑，verify why.md#requirement-commandprocessor-生命周期与-io-循环拆分-scenario-accept-过载拒绝与指标审计保持一致
- [√] 4.2 新增 `core/src/main/java/com/javasleuth/command/server/ConnectionAcceptor.java`：封装 accept 循环、连接上限/过载拒绝与回包（保持 audit/metrics 语义），verify why.md#requirement-commandprocessor-生命周期与-io-循环拆分-scenario-accept-过载拒绝与指标审计保持一致
- [√] 4.3 新增 `core/src/main/java/com/javasleuth/command/server/ShutdownCoordinator.java`：封装 graceful/emergency shutdown 的步骤编排，并确保幂等，verify why.md#requirement-commandprocessor-生命周期与-io-循环拆分-scenario-graceful-shutdown-行为保持一致
- [√] 4.4 重构 `core/src/main/java/com/javasleuth/command/CommandProcessor.java`：保留 start/stop/restart 门面，内部组合上述组件与现有 `CommandClientHandler`，verify why.md#requirement-commandprocessor-生命周期与-io-循环拆分-scenario-graceful-shutdown-行为保持一致

## 5. Testing：单测 + 集成回归（锁定协议与编排语义）
- [√] 5.1 新增/调整 Launcher 单测：args 解析、握手 KV 解析、connectHost 解析、stream policy（建议放在 `launcher/src/test/java/...`），verify why.md#requirement-可测试性与回归基座-scenario-单测覆盖核心解析与编排
- [√] 5.2 新增 server 单测：`ConnectionAcceptor` 过载拒绝判断与回包路径（mock socket/stream 或可注入 writer），verify why.md#requirement-commandprocessor-生命周期与-io-循环拆分-scenario-accept-过载拒绝与指标审计保持一致
- [√] 5.3 新增轻量集成测试：启动 `CommandProcessor`（loopback + ephemeral port）→ `ProtocolClient` 完成握手并执行轻量命令闭环，verify why.md#requirement-可测试性与回归基座-scenario-集成测试覆盖协议闭环

## 6. Security Check
- [√] 6.1 执行安全检查（per G9）：headless 模式下 `--insecure` 不允许静默确认；脚本文件读取走受控校验；签名/connId 绑定语义不变

## 7. Documentation Update
- [√] 7.1 更新知识库：`helloagents/wiki/modules/launcher.md`（新增 headless/组件边界说明）与 `helloagents/wiki/modules/command.md`（server lifecycle 组件化说明）
- [√] 7.2 更新 `helloagents/wiki/arch.md` ADR 索引（新增 ADR-012/ADR-013）与 `helloagents/CHANGELOG.md`（记录重构要点）
- [√] 7.3 若对外 CLI 参数新增：同步 `docs/usage/getting-started.md` 与 `README.md` 使用示例

## 8. Testing
- [√] 8.1 `mvn test`：验证全量单测通过
- [√] 8.2 `mvn -DskipTests package`：验证产物可打包（launcher/agent/bootstrap/core 均可构建）
