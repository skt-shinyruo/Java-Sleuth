# launcher

## Purpose
提供 CLI 入口、Attach 流程与交互会话。

## Module Overview
- **Responsibility:** JVM 选择、Attach、Socket 交互
- **Status:** ✅Stable
- **Last Updated:** 2026-02-03

## Specifications

### Requirement: 交互式诊断入口
**Module:** launcher
用户通过 CLI 与目标 JVM 内服务交互。

#### Scenario: 连接本地命令服务
前置：Agent 已启动命令服务  
- 连接 host:port（host 来自 `server.bind.address` 或握手返回的 bind 信息；`0.0.0.0/::` 会回退到 `127.0.0.1` 用于本机 attach）
- 逐行发送命令并读取响应

### Requirement: JVM 列表展示与选择一致
**Module:** launcher
过滤 JVM 列表后重新编号，保证“展示序号=可选序号”，避免选错目标进程。

#### Scenario: 过滤 Java-Sleuth 自身并正确选择 PID
前置：本机存在多个 Java 进程  
- 列表过滤掉 Java-Sleuth 自身进程  
- 展示序号连续、选择范围与展示一致  
- 选择后 attach 到正确 PID

### Requirement: 握手协商与协议升级
**Module:** launcher
通过 HELLO/CONFIG 握手从服务端获取实际协议与能力，并在需要时升级到二进制帧通道。

#### Scenario: HELLO/CONFIG 握手
前置：连接建立并收到 welcome 行  
- 发送 `HELLO v=1 protocols=...`
- 读取 `CONFIG ... protocol=<legacy|framed|binary>`
- 若选择 binary：发送 `UPGRADE BINARY` 并切换到 BinaryFrame 通道
- 在 `security.mode=hmac` 且握手开启时，Launcher 采用 `SIG v=2`（sid 绑定到握手协商的 connId）

### Requirement: 分帧协议与流式支持
**Module:** launcher
支持 framed 模式、binary 模式与流式命令输出。

#### Scenario: framed 模式交互
前置：配置开启 framed  
- CMD/STREAM 前缀发送
- DATA/END/ERR 分帧读取

#### Scenario: binary 模式交互
前置：握手选择 binary  
- REQUEST/DATA/ERR/END 二进制帧读写
- 支持包含换行/长输出的严格分帧

### Requirement: 可选请求签名（security.mode=hmac）
**Module:** launcher
当启用 hmac 时，Launcher 会将命令封装为 `SIG ... cmd=<base64url>` 发送，以提供完整性校验与基础防重放。

### Requirement: 本机单次排障的显式不安全开关（--insecure）
**Module:** launcher / security
允许在“明确知情 + 本机可信”的场景下临时关闭安全模式，但必须交互确认以降低误用风险。

#### Scenario: 使用 --insecure 启动
前置：用户需要在本机快速排障且确认不会被端口转发/容器网络/代理暴露  
- 通过 `--insecure` 启动 Launcher
- Launcher 会提示风险并要求输入 `I UNDERSTAND` 才继续
- attach 参数会强制下发 `security.mode=off`

### Requirement: 启动/发布稳定化（JarLocator）
**Module:** launcher / util
避免 jar 名称/版本号硬编码与“必须从项目目录启动”的脆弱假设。

#### Scenario: 任意工作目录启动并自动定位 agent jar
前置：以 `java -jar` 运行 launcher（fat jar）或 IDE classpath 运行  
- 优先使用 `-Dsleuth.agent.jar=<path>` / 环境变量 `SLEUTH_AGENT_JAR` 覆盖
- 运行在 jar 内：基于 `CodeSource` 定位自身 jar
- IDE/classpath：回退扫描 `target/*-jar-with-dependencies.jar` 或当前目录

### Requirement: attach 时安全自举（HMAC secret 自动下发）
**Module:** launcher / security
在默认配置下避免“security.mode=off + 空 secret”的误用风险，并保证 Launcher 与 Agent 的安全配置一致。

#### Scenario: attach 自动启用 HMAC 并同步会话角色
前置：`security.bootstrap.hmac.on.attach=true`  
- Launcher attach 时生成随机 `security.hmac.secret` 并通过 agentArgs 下发到目标 JVM
- Launcher 本地同时启用 `security.mode=hmac` 并同步 `security.hmac.session.role`，保证后续命令发送会签名
- 目标 JVM 侧按 `security.hmac.session.role` 自举初始会话角色（避免必须先 `auth`）

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
- agent
- command

## Change History
- 202601281100_init_kb (planned)
- 202601281207_sleuth_plugin_stream (history/2026-01/202601281207_sleuth_plugin_stream/) - framed/stream 协议支持
- 202601281301_sleuth_handshake_secure_frames (history/2026-01/202601281301_sleuth_handshake_secure_frames/) - handshake + binary + 可选 SIG 签名
- 202601291031_fix-5-issues (history/2026-01/202601291031_fix-5-issues/) - 进程选择修复与连接地址解析增强
- 202602011222_sleuth_hardening_bootstrap (history/2026-02/202602011222_sleuth_hardening_bootstrap/) - jar 自动定位 + HMAC 自举与启动稳定性增强
