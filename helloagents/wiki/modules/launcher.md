# 模块：launcher（本机启动器/客户端）

## 1. 模块职责

`launcher` 负责运行在 **诊断侧（本机/运维机）** 的编排与交互：

- 发现并选择目标 JVM（PID/进程列表）
- 通过 Attach API 将 agent 注入目标 JVM
- 作为协议客户端与目标 JVM 内的命令服务端完成握手与命令交互
- 提供交互式 UI（JLine）与 headless 脚本化运行模式

入口类：`com.javasleuth.launcher.SleuthLauncher`

## 2. 设计约束：SleuthLauncher = Composition Root

为避免“启动器 God class”，`SleuthLauncher` 仅承担：

- CLI 参数解析
- 组件装配（选择 discovery/attach/client/shell 的实现）
- 流程编排（失败路径返回明确 exit code）

业务逻辑拆分在子组件中，便于测试与演进。

## 3. 关键子组件（按包）

### 3.1 `cli`

- `LaunchMode`：运行模式（`interactive` / `headless`）
- `LauncherArgs`：参数模型与校验

### 3.2 `jvm`

- `JvmDiscovery` / `AttachJvmDiscovery`：发现可 Attach 的 JVM 列表
- `JvmSelector`：选择策略抽象
- `JlineJvmSelector`：交互式选择实现（JLine UI）

### 3.3 `attach`

- `AttachApi`：Attach 能力抽象
- `ToolsAttachApi`：基于 `com.sun.tools.attach` 的实现
- `AgentArgsBuilder`：构造 agent 参数（避免散落的字符串拼接）
- `AgentAttacher`：执行 attach + loadAgent

### 3.4 `client`

- `ProtocolClient`：协议客户端门面（连接/握手/收发/关闭）
- `HandshakeClient` / `HandshakeConfig`：握手协商与安全配置
- `KvLineCodec`（foundation）：`HELLO/CONFIG/SIG` 的通用 KV 行解析 SSOT（通过 `HandshakeClient` 间接使用），降低协议解析漂移风险。
- `ConnectHostResolver`：连接目标解析（host/port 约定集中化）
- `ProtocolOutput` / `ConsoleProtocolOutput`：输出抽象（便于测试与 headless）

### 3.5 `shell`

- `InteractiveShell`：交互式命令循环（JLine）
- `HeadlessRunner`：脚本化执行（`--cmd` / `--script`）
- `StreamPolicy` / `DefaultStreamPolicy`：输出/流式处理策略（便于自动化）

## 4. Headless 安全边界

headless 模式属于“非交互自动化执行”，默认更保守：

- 支持 `--fail-fast`（遇到错误立即退出，适合 CI/脚本）
- 若允许降低安全约束（如 `--insecure`），必须显式二次确认：
  - 需要同时提供 `--insecure-confirm "I UNDERSTAND"` 才会生效

该约束用于避免脚本/管道中无意间静默降级安全模式。

## 5. 测试要点

launcher 的测试覆盖重点：

- 参数解析/校验（`LauncherArgsTest`）
- host/port 解析与握手逻辑（`ConnectHostResolverTest`、`HandshakeClientTest`）
- headless 输出策略（`DefaultStreamPolicyTest`）
- 协议集成（本地启动 CommandProcessor → 握手 → `version`）
