# Change Proposal: 修复 5 个核心实现问题（协议/安全/插桩重构）

## Requirement Background
当前 Java-Sleuth 已具备 Attach 注入、交互式命令与 ASM 插桩等基础能力，但在真实场景/生产化使用上仍存在一些“可用性缺陷 + 协议稳定性风险 + 安全默认偏松 + 会话治理缺陷 + 插桩覆盖不足”的问题，会导致：
- 用户选择目标 JVM 时出现“序号与实际进程不一致”的困惑，甚至选错进程。
- Agent 端绑定非回环地址时，CLI 仍固定连接 localhost，导致无法建立会话。
- 文本协议与二进制协议升级混用不同缓冲流封装，存在读写边界错位与升级不稳定风险。
- 默认允许匿名 viewer 且 security.mode=off，遇到非回环绑定时存在明文口令/命令被窃听与滥用的风险。
- 登录锁定与客户端标识解析实现不严谨，可能造成锁定策略失效或误伤。
- 插桩默认跳过所有包含 `$$` 的类（Spring/CGLIB 代理常见），影响 watch/trace 的真实命中率；同时插桩日志过于“每次 transform 都打印”，生产场景会刷屏。

本次变更允许进行较大改动（包含架构与使用方式调整），目标是一次性解决上述 5 个问题，并为后续扩展（更强协议/安全/可观测性）打好基础。

## Change Content
1. 修复 JVM 进程列表展示与选择逻辑，保证“展示序号=可选序号”，并改进连接地址解析（支持非 localhost 绑定）。
2. 重构客户端/服务端传输层与协议升级流程，消除 BufferedReader/PrintWriter 与 DataInputStream/DataOutputStream 混用导致的缓冲风险。
3. 收敛安全默认策略与危险命令权限：在非回环绑定时强制启用请求完整性保护；对可变更运行态的命令（如 sysprop 写入）做更严格的 RBAC/参数校验；调整默认匿名策略。
4. 修复 AuthenticationManager 的锁定/清理逻辑：基于真实客户端标识做限流与锁定窗口，并按时间过期清理而不是全量清空。
5. 优化 Agent 生命周期与插桩覆盖：避免 Agent 线程阻止 JVM 正常退出；改进类过滤策略以支持常见代理类；将插桩日志纳入可配置的日志等级/降噪策略。
6. 修复输入校验与实际命令格式不一致的问题（特别是 `redefine`/`heapdump`），并修正文件访问权限判断（读写权限、相对路径）。
7. 修复审计与配置输出中的敏感信息泄露：禁止将密码/secret/session 等写入控制台与日志文件，统一脱敏策略。
8. 补齐资源治理与 DoS 防护：落实 `server.max.connections` 限制、命令执行 `performance.command.timeout`、文本行最大长度限制（与 frame maxPayload 对齐）。
9. 修正 PerformanceOptimizer 的维护策略：移除默认定时 `System.gc()`（改为可配置，默认关闭），避免生产环境 STW 风险。
10. 修复配置/文档与代码不一致：移除或补齐未实现的配置项说明（例如 `security.mode=tls`），确保对外文档不产生误导。

## Impact Scope
- **Modules:**
  - launcher
  - command / command.protocol
  - security
  - config
  - agent / enhancement / monitor
  - docs（使用方式与安全说明需要同步）
- **Files:**
  - `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`
  - `src/main/java/com/javasleuth/command/CommandProcessor.java`
  - `src/main/java/com/javasleuth/command/protocol/*`
  - `src/main/java/com/javasleuth/security/*`
  - `src/main/java/com/javasleuth/enhancement/SleuthClassFileTransformer.java`
  - `src/main/java/com/javasleuth/agent/SleuthAgent.java`
  - `README.md`, `docs/usage/commands.md`
- **APIs:**
  - CLI 连接/握手行为（可能新增参数/默认策略变化）
  - `sysprop` / `auth` 等命令的行为与权限（可能需要兼容层或迁移说明）
- **Data:** 无持久化数据结构变更（仅运行态会话/缓存结构调整）

## Core Scenarios

### Requirement: 修复进程选择与连接地址
**Module:** launcher
修复进程列表过滤后序号不一致问题；当 Agent 绑定非 localhost 时，CLI 可按协商/配置连接正确地址。

#### Scenario: 本地 Attach 并进入交互会话
在同机启动多个 Java 进程（包含 Java-Sleuth 自身）。
- 展示列表中不出现 Java-Sleuth 自身进程，且序号连续可选。
- 用户选择序号后 attach 到正确 PID。
- attach 成功后 CLI 使用正确 host/port 建立会话，进入 `sleuth>` 交互。

### Requirement: 统一传输层与协议升级（消除混用缓冲风险）
**Module:** command / protocol / launcher
重构 I/O：文本命令、握手、升级到二进制/帧协议均使用同一套底层流与显式状态机，避免“先读一部分又换封装”的不确定性。

#### Scenario: 握手协商并升级到二进制模式
客户端启用握手并选择 `protocol=binary`。
- 服务端正确返回 CONFIG，客户端升级成功。
- 二进制帧通信无乱码/无卡死，PING/PONG 正常。
- watch/trace streaming 在升级后仍可持续输出。

### Requirement: 安全默认策略与 RBAC 收敛
**Module:** security / command
在不牺牲可用性的前提下，收敛“默认可远程明文控制”的风险面：对非回环绑定强制完整性保护；对危险命令（写入/热替换/heapdump）收紧权限与参数验证。

#### Scenario: 非回环绑定启动与安全保护
将 server.bind.address 配置为非回环地址（如 `0.0.0.0` / 局域网 IP）。
- 若未启用完整性保护，则服务端拒绝启动并给出明确提示（或自动切换到安全模式）。
- 命令在完整性校验失败时被拒绝，并记录审计事件。
- `sysprop` 读取与写入权限分离（写入仅允许高权限角色/安全模式）。

### Requirement: 修复登录锁定/限流与客户端标识解析
**Module:** security
修复锁定策略与客户端 key 解析缺陷，避免“空 key”导致的误伤与“定时清空 attempts”导致的锁定失效。

#### Scenario: 连续失败后锁定并按时间解除
同一客户端连续输入错误凭证。
- 达到阈值后进入锁定窗口，锁定期间拒绝认证。
- 锁定窗口结束后可重新认证。
- 清理线程仅清理过期记录，不全量清空。

### Requirement: Agent 生命周期/插桩覆盖与日志降噪
**Module:** agent / enhancement / monitor
避免 agent 线程阻止 JVM 正常退出；允许常见代理类被插桩；将插桩日志控制为可配置并避免刷屏。

#### Scenario: Spring/CGLIB 代理类 watch/trace 可命中
对包含 `$$EnhancerBySpringCGLIB$$` 的类进行 watch/trace。
- 插桩不再被默认跳过（或可配置）。
- 日志在 INFO 级别下不刷屏，DEBUG 才输出详细 transform 信息。

### Requirement: 修复输入校验与文件权限判断不匹配
**Module:** security / command / util
当前 `InputValidator` 与部分命令真实参数格式存在不匹配（例如 `redefine` 的第 1 个参数是类名而不是文件路径），会导致默认开启输入校验时某些命令不可用；同时 `SecurityValidator.canAccessFile` 的读写判断与相对路径支持存在缺陷，会误拒绝合法路径或放过不安全写入。

#### Scenario: redefine 在启用 input validation 时可正常执行
在 `security.input.validation=true` 的默认情况下执行 redefine。
- `redefine <class-name> <class-file-path>` 不被 InputValidator 误判为非法。
- 文件路径校验对 `.class` 文件生效，且仅在需要时检查写权限/读权限。

#### Scenario: heapdump 支持相对路径且按写权限校验
执行 `heapdump myapp.hprof` 或 `heapdump --file=myapp.hprof`。
- 允许写入当前工作目录（可写时）。
- 对已有文件要求可写，对新文件要求父目录可写。

### Requirement: 修复审计/控制台日志敏感信息泄露
**Module:** security / config
当前审计日志会记录完整命令参数，可能包含 `auth` 密码、`security.hmac.secret`、sessionId 等敏感信息；并且部分配置修改会直接打印明文值到 stdout。

#### Scenario: auth/config 不泄露密码与 secret
执行 `auth admin <password>`、`config set security.hmac.secret <secret>`。
- 审计日志与控制台输出中不出现明文 password/secret。
- 仍保留可追踪性（记录“已脱敏”或 hash/长度等非敏感摘要）。

### Requirement: 补齐资源限制与超时控制
**Module:** command / protocol / config
当前 `server.max.connections` 与 `performance.command.timeout` 等配置存在但未生效；文本模式缺少最大行长度限制，可能被超长输入拖垮（内存/CPU）。

#### Scenario: 超限连接被拒绝且不影响现有连接
并发连接数超过 `server.max.connections`。
- 服务器拒绝新连接（返回简短提示并关闭），并记录审计事件。
- 现有连接不受影响。

#### Scenario: 长耗时命令按 timeout 中断并返回错误
执行耗时命令或人为制造阻塞。
- 超过 `performance.command.timeout` 后返回超时错误（不导致线程永久占用）。
- 审计与 metrics 记录超时事件。

#### Scenario: 超长文本行被安全拒绝
发送超长单行输入（超过限定长度）。
- 服务端在读入阶段拒绝并断开（或返回错误），避免 OOM。

### Requirement: 修正性能维护策略（禁用默认 System.gc）
**Module:** util
PerformanceOptimizer 当前默认每 60 秒触发一次 `System.gc()`，可能带来明显 STW 与性能抖动，不适合生产默认值。

#### Scenario: 默认不触发强制 GC，可配置开启
- 默认配置下不主动调用 `System.gc()`。
- 如确有需要，可通过配置开关显式启用，并记录审计事件。

### Requirement: 配置/文档一致性修复
**Module:** docs / config
配置文件、文档与代码存在部分不一致（例如 `security.mode=tls` 在配置注释中出现但代码未支持；文档提及的某些 key 在代码中不存在）。

#### Scenario: 用户按文档配置不会落入“假安全/无效配置”
- 文档与默认配置只暴露真实支持的选项。
- 对不支持的模式/配置给出明确提示或迁移建议。

## Risk Assessment
- **Risk:** 协议与安全默认策略变化可能影响现有脚本/使用习惯
  - **Mitigation:** 提供兼容模式开关（legacy/off），并更新 README 与命令文档给出迁移说明
- **Risk:** 传输层重构引入边界条件 bug（升级、断连、流式输出）
  - **Mitigation:** 增加协议单测/集成测试，提供本地 demo 回归脚本
- **Risk:** 放开代理类插桩可能带来额外开销
  - **Mitigation:** 通过配置控制过滤策略、日志等级与采样/限流
