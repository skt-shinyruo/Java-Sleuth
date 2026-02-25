# Change Proposal: Launcher/CommandProcessor 去 God class（可插拔运行模式）

## Requirement Background
当前 `SleuthLauncher` 与 `CommandProcessor` 存在明显的“编排类职责过载（God class）”问题：

1. **Launcher 侧职责过载**：`SleuthLauncher` 同时负责 JVM 列表与选择、Attach 注入、配置/安全自举确认、协议握手、IO 读写循环、交互 UI（JLine）。
2. **Server 侧职责过载（已部分缓解但仍可优化）**：`CommandProcessor` 仍承担启动自举（日志/JobManager/HMAC secret）、accept 循环、过载拒绝回包、线程池背压、metrics/audit 生命周期编排、shutdown/restart 编排等多类关注点。

由此带来的成本与风险：
- **演进成本高**：协议/安全/交互/Attach 任一修改都可能牵动其它路径，回归范围大。
- **测试成本高**：大量逻辑与 `System.console` / JLine / Socket / Attach API 强耦合，难以用单测隔离验证；只能依赖端到端手工验证。
- **单点冲突热点**：多人协作或持续迭代时更易产生冲突与回滚困难。

本变更目标是在不破坏默认交互体验与协议语义的前提下，将这些职责解耦为可组合组件，并引入“可插拔运行模式”（Interactive/Headless），从工程结构上降低长期维护成本。

## Change Content
1. **Launcher 组件化 + 运行模式抽象**
   - 将 JVM 发现/选择、Attach 注入、协议客户端、交互 UI 拆为独立组件。
   - 引入 `LaunchMode`：默认 `interactive` 行为保持兼容；新增 `headless`（单次命令/脚本批处理）作为可选能力。
2. **协议客户端抽象（client-side state machine）**
   - 将握手协商、binary/framed 协议收发、streaming 行为封装成 `ProtocolClient`，Launcher 仅做装配与 UI。
3. **CommandProcessor 进一步去编排巨型类**
   - 将启动自举、连接 accept 与过载拒绝、shutdown 编排拆分为独立组件（仍保持 `CommandClientHandler` 作为单连接处理器）。
4. **测试基座与回归锁定**
   - 增加可单测的纯逻辑组件测试（args/握手/stream policy/连接 host 解析等）。
   - 增加轻量集成测试基座：启动本地 `CommandProcessor` → 通过 `ProtocolClient` 走握手与请求闭环，锁定协议与编排行为。

## Impact Scope
- **Modules:**
  - `launcher`（拆分 UI/Attach/ProtocolClient，并新增 headless mode）
  - `core`（进一步拆分 `CommandProcessor` 生命周期/accept/shutdown 编排）
  - `foundation`（如需新增协议/客户端公共抽象，可考虑下沉；优先保持改动最小）
- **Files:**
  - `launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`（退化为 composition root）
  - 新增 `launcher/.../attach/*`、`launcher/.../client/*`、`launcher/.../shell/*`、`launcher/.../jvm/*`、`launcher/.../cli/*`
  - `core/src/main/java/com/javasleuth/command/CommandProcessor.java`（拆分与瘦身）
  - 新增 `core/src/main/java/com/javasleuth/command/server/*` 中的 server lifecycle 组件
- **APIs:**
  - 默认交互式启动不变（保持兼容）
  - 新增可选 CLI 参数（headless）：`--pid` / `--cmd` / `--script` / `--fail-fast` 等（以实现为准）
- **Data:** N/A

## Core Scenarios

### Requirement: Launcher 组件化与运行模式
**Module:** launcher
将 `SleuthLauncher` 拆分为“可组合组件”，并引入 Interactive/Headless 两种运行模式，默认行为保持兼容。

#### Scenario: 默认交互模式保持兼容
前置：用户以常规方式启动 Launcher  
- 启动横幅/提示不变（或仅做非破坏性调整）
- 仍可交互选择目标 JVM（过滤 Sleuth 自身进程）
- 仍可完成 attach 注入并进入 `sleuth>` 交互会话
- HELLO/CONFIG 握手、binary 升级与 framed/binary IO 行为保持兼容

#### Scenario: Headless 单次命令执行
前置：用户希望脚本化执行并快速退出  
- 可通过 CLI 直接指定目标（例如 `--pid <pid>`）并 attach
- 执行 `--cmd "<command>"` 后输出结果并退出
- 失败时返回非 0 退出码（可选），便于 CI/自动化编排

#### Scenario: Headless 脚本批量执行
前置：用户提供脚本文件（每行一条命令，支持注释/空行）  
- Launcher 按顺序发送命令并输出结果
- 支持 `--fail-fast`（遇到错误立即停止）或 `--continue-on-error`
- 结束后主动关闭连接并退出

### Requirement: 协议客户端解耦
**Module:** launcher / foundation
把协议状态机从 `SleuthLauncher` 中抽离，形成可测试、可复用的 `ProtocolClient`。

#### Scenario: 握手协商与协议升级
前置：与目标 JVM 的命令服务建立 TCP 连接  
- 发送 `HELLO ...`，读取 `CONFIG ...` 并解析协商结果
- 若协商为 binary：发送 `UPGRADE BINARY` 并切换到二进制帧通道
- `security.mode=hmac` 时签名逻辑保持一致（`connId` 绑定）

#### Scenario: 流式命令输出与帧读取
前置：协商得到 `streaming=true` 且命令支持 streaming  
- framed：使用 `STREAM <signed>` 并按 DATA/ERR/END 帧读取
- binary：使用 REQUEST(stream=true) 并按 DATA/ERR/END 帧读取

### Requirement: CommandProcessor 生命周期与 IO 循环拆分
**Module:** core
`CommandProcessor` 聚焦“server lifecycle 门面”，将启动自举、accept 循环、过载拒绝与 shutdown 编排拆分为独立组件，降低修改牵连。

#### Scenario: accept 过载拒绝与指标审计保持一致
前置：达到 `server.max.connections` 或 executor 饱和  
- 新连接被拒绝并快速关闭
- audit/metrics 记录语义保持一致（错误码/计数不倒退）

#### Scenario: graceful shutdown 行为保持一致
前置：收到 stop/shutdown 或 JVM shutdown hook 触发  
- 不再接受新连接
- 等待已有连接/线程池按既定超时退出
- metrics/audit 最终收尾执行，避免丢审计

### Requirement: 可测试性与回归基座
**Module:** launcher / core
通过组件化与可注入依赖，降低对真实终端/真实 Attach 的依赖，增加可重复的自动化回归。

#### Scenario: 单测覆盖核心解析与编排
- args 解析、connectHost 解析、握手 kv 解析、stream policy 具备单测
- server accept/过载拒绝判断具备单测

#### Scenario: 集成测试覆盖协议闭环
- 在测试进程内启动 `CommandProcessor`（本机回环）
- 使用 `ProtocolClient` 走握手并执行至少 1 条轻量命令（例如 `version`/`help`）

## Risk Assessment
- **Risk:** 交互行为/协议语义回归（握手、帧读取、streaming、签名绑定），或 CLI 参数变更导致脚本不可用。  
  **Mitigation:** 保持默认 interactive 路径兼容；先引入新组件并以适配器方式接入；新增单测与集成测试锁定行为；每步重构后跑 `mvn test`。
- **Risk:** headless 模式弱化安全确认（例如 `--insecure`）。  
  **Mitigation:** headless 下对高风险开关增加显式确认参数（例如二次确认 token / `I_UNDERSTAND` 等价机制），避免静默降级。

