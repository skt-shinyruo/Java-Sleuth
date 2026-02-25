# Change Proposal: 启动/安全/插件/Trace 综合加固（Hardening & Bootstrap）

## Requirement Background
Java-Sleuth 作为类似 Arthas 的轻量诊断工具，典型使用方式是：Launcher 动态 Attach 目标 JVM，注入 Agent 后通过 socket 进行交互式诊断与插桩观察。

当前实现已经具备较完整的命令体系与协议/安全框架，但在“可发布性、默认安全、可维护性与跨平台一致性”方面仍存在一些高概率踩坑点：
1. **启动/发布对版本与目录过度耦合**：Agent jar 名称在脚本与代码中硬编码版本；Launcher 依赖相对路径 `target/`；从非项目根目录启动会失败；`sleuth.sh` 使用 `grep -P` 导致 macOS 上兼容性不稳。
2. **安全默认与内置口令易误用**：默认 `security.mode=off` 且 `security.hmac.secret` 为空；认证仍存在硬编码 demo 口令（admin/operator/viewer），与“生产可用”目标相冲突。
3. **插件机制存在供应链与资源释放风险**：默认启用插件目录扫描并加载 jar，缺少显式启用开关与 allowlist/校验；`URLClassLoader` 未明确关闭，Windows 上易出现 jar 文件锁定/句柄泄露/热更新困难。
4. **trace 采样破坏调用链正确性**：当前采样为“每个事件独立采样”，可能出现 entry/exit 不配对，导致调用树深度与链路结果不可信；同时默认全量采样在高 QPS 场景下容易引入额外开销与事件丢弃。
5. **维护性与文档一致性问题**：部分异常直接 `printStackTrace` 或吞掉，可能污染目标 JVM 的 stdout/stderr；审计日志默认写工作目录并输出到控制台，生产环境易刷屏/权限失败；`mc/tt` 等命令的文档与实现存在漂移。

本次变更目标是在尽量保持现有交互体验与兼容性的前提下，实现“可发布（版本无感）+ 安全自举（默认更安全）+ 插件可控（默认关闭）+ trace 结果可信 + 文档一致”的综合加固。

## Change Content
1. **启动/发布稳定化**：Launcher 自动定位自身 jar（作为 agent jar），脚本与示例脚本使用通配符定位 jar；修复从任意 cwd 启动与 macOS 兼容性问题。
2. **安全自举（secure-by-default）**：Launcher 在 attach 时自动生成并下发 HMAC secret（同时在 Launcher 侧启用签名发送）；移除硬编码 demo 口令，改为显式配置/环境变量或可选的“安全自举令牌”模式。
3. **插件加载加固**：新增 `plugins.enabled` 显式开关（默认关闭）；支持 allowlist 与可选 sha256 校验；管理插件 classloader 生命周期并在 shutdown 时释放资源，提供 Windows 友好的加载策略（可选 staging copy）。
4. **trace 采样正确性修复**：采样从“每事件独立”调整为“按调用栈一致/可配”，保证 entry/exit 配对与深度一致；默认采样率调整为更保守值并允许按命令/按 trace 覆盖。
5. **日志/文档一致性治理**：统一错误输出到 `SleuthLogger` 并按 level 控制；审计日志文件路径/控制台输出可配置；修正文档中 `mc/tt`、命令数量与实际实现的差异，并同步内部知识库。

## Impact Scope
- **Modules:** launcher / agent / config / security / command / monitor / util / docs / scripts
- **Files:** 预计涉及 15-30 个文件（Java + properties + docs + scripts）
- **APIs:** 交互协议保持兼容（新增配置项与更安全默认行为；保留 legacy/off 兼容开关）
- **Data:** 无持久化数据结构变更（仅增加运行时配置项与少量内存状态）

## Core Scenarios

### Requirement: 启动与发布稳定化（Bootstrap/Packaging）
**Module:** launcher + scripts
去除对 jar 版本与 cwd 的硬编码依赖，让用户升级版本后无需修改脚本/代码即可启动并 attach。

#### Scenario: 任意工作目录可启动并自动找到 jar
- 前置：用户在任意目录执行 `./sleuth.sh` 或 `sleuth.bat`
- 期望：
  - 脚本自动定位项目目录并找到 `target/java-sleuth-*-jar-with-dependencies.jar`
  - Launcher 能从运行时定位自身 jar（IDE 启动时则回退到扫描 target/）

#### Scenario: macOS 上无需 `grep -P` 也能正常运行
- 前置：macOS 默认 `grep` 不支持 `-P`
- 期望：脚本通过“tools.jar 是否存在”来判断是否需要追加 classpath，不再依赖版本解析

### Requirement: 安全默认与认证自举（Secure-by-default）
**Module:** launcher + config + security
将“安全配置”从易误用的静态默认值，升级为“自举式安全”（默认更安全、配置更少、尽量不破坏体验）。

#### Scenario: attach 自动启用 HMAC 签名（无需用户手工配置 secret）
- 前置：用户使用 Launcher attach
- 期望：
  - Launcher 生成随机 `security.hmac.secret` 并通过 agent args 注入目标 JVM
  - Launcher 自身也使用同一 secret 对命令签名发送（用户继续输入原始命令即可）
  - 若用户显式配置了 secret，则复用用户配置，不覆盖

#### Scenario: 移除硬编码 demo 口令，避免“生产默认口令”
- 前置：生产环境默认不应存在固定口令
- 期望：
  - 默认不再内置 `sleuth_admin_2023!` 等固定口令
  - 认证方式改为：显式配置/环境变量提供口令，或启用“自举令牌/仅 HMAC”模式

### Requirement: 插件加载安全与资源释放（Plugin hardening）
**Module:** command + config + security
避免插件默认开启导致的供应链风险，并解决 Windows 上 jar 锁定与 classloader 资源泄露问题。

#### Scenario: 插件默认关闭，启用需显式开关
- 前置：默认配置
- 期望：`plugins.enabled=false` 时不扫描/加载插件目录

#### Scenario: allowlist/sha256 校验通过才加载
- 前置：用户启用插件
- 期望：
  - 仅加载 allowlist 中的 jar（可选：同时校验 sha256）
  - 未通过校验的插件被拒绝并记录审计事件

#### Scenario: 释放资源，避免 Windows jar 锁定
- 前置：插件已加载，Agent shutdown 或 stop
- 期望：关闭/释放插件 classloader（必要时采用 staging copy 策略降低锁定影响）

### Requirement: Trace 采样正确性（Sampling consistency）
**Module:** monitor
保证 trace 输出的调用层级与事件配对一致，避免采样导致的“看起来像 bug”的调用树错乱。

#### Scenario: entry/exit 采样一致，调用树稳定
- 前置：开启 trace 且采样率 < 1
- 期望：
  - 同一次方法调用的 entry/exit 要么都记录，要么都不记录
  - depth 计算与 SUB_METHOD_CALL 事件不因采样而错乱

#### Scenario: 默认采样更保守，降低高 QPS 影响
- 前置：生产高 QPS 场景误用 trace
- 期望：默认采样率调整为较小值（可配置/可命令覆盖），并提供丢弃/采样指标可观测

### Requirement: 可维护性与文档一致性（Maintainability & Docs）
**Module:** util + security + docs
降低对目标 JVM 的副作用（stdout/stderr、文件路径），并让文档真实反映实现。

#### Scenario: 统一日志与异常输出，避免目标 JVM 刷屏
- 期望：
  - 将 `printStackTrace` 替换为 `SleuthLogger`
  - 关键错误仍可见，但异常栈仅在 DEBUG 级别输出

#### Scenario: 审计日志路径/控制台输出可配置
- 期望：
  - 审计/安全日志文件路径可配置到可写目录
  - 控制台输出可关闭或受 level 控制

#### Scenario: 文档与实现对齐（mc/tt/命令清单）
- 期望：
  - `mc` 文档改为文件路径输入，与校验逻辑一致
  - `tt` 命令与其他内置命令补齐文档（不再宣称“仅 20 个命令”）

## Risk Assessment
- **风险：默认启用 HMAC 可能影响第三方客户端直连（需要签名格式）**  
  **缓解：** 保留 `security.mode=off` 的 loopback 兼容；在文档中明确“推荐使用 Launcher”；为协议提供清晰错误提示与示例。
- **风险：移除硬编码口令可能影响现有脚本/习惯**  
  **缓解：** 提供 `production.dev.features=true` 或显式 `security.auth.allow.insecure_defaults=true`（仅开发）作为迁移通道；默认保持安全。
- **风险：插件 allowlist/校验增加使用成本**  
  **缓解：** 默认关闭插件；提供可选的“仅 allowlist（不校验 sha256）”与“allowlist+sha256”两级策略，并给出模板配置。
- **风险：trace 采样语义变更可能改变输出形态**  
  **缓解：** 将变更限定为“修复不配对/错层级”并保持字段兼容；补充单测与回归脚本验证。 
