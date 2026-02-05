# command

## Purpose
定义命令接口、注册与执行链路。

## Module Overview
- **Responsibility:** 命令解析、校验、执行、输出
- **Status:** ✅Stable
- **Last Updated:** 2026-02-05

## Specifications

### Requirement: 命令执行链路
**Module:** command
从输入到执行的完整处理流程。

#### Scenario: 命令被解析并执行
前置：客户端已连接  
- 输入校验
- 命令执行
- 结果清洗与返回

实现要点（SSOT）：
- `CommandPipeline` 作为对外门面（Facade），对外签名保持稳定
- 内部执行链路显式化为 Step/Interceptor 链（`com.javasleuth.command.pipeline.*`），避免在单个类中堆叠条件分支
  - precheck：`PrecheckPipeline`（Validate/Authz/DangerousConfirm）
  - sync：`PipelineChain` + `CacheInterceptor` + `OutputSanitizeInterceptor` + `CommandExecutionEngine`
  - stream：`GuardedStreamInterceptor`（按 chunk sanitize + close/error 语义收敛）+ `CommandExecutionEngine`
- confirm 参数剥离 SSOT：`CommandArgs.stripConfirmArgs`（校验/授权阶段不应受 confirm token 影响）
- 参数解析与校验 SSOT（数值/范围/错误码）：`CommandArgs.getInt/getLong/requireInt/requireLong`（统一错误码：`E_ARGS_MISSING`/`E_ARGS_INVALID`/`E_ARGS_RANGE`；缺省用默认值，越界/非法格式返回可读错误）
- Locale 归一化 SSOT（核心链路）：命令名使用 `toLowerCase(Locale.ROOT)`；握手关键字使用 `regionMatches(ignoreCase=true)`，避免默认 Locale 影响命令识别

### Requirement: 统一传输层与协议状态机（握手/升级）
**Module:** command / protocol
服务端与客户端在同一底层 `InputStream/OutputStream` 上完成文本行协议与二进制帧协议的协商与升级，避免混用多层缓冲导致的边界错位。

#### Scenario: HELLO/CONFIG 协商并升级到 binary
前置：`protocol.handshake.enabled=true`  
- 客户端发送 `HELLO ... protocols=legacy,framed,binary`  
- 服务端返回 `CONFIG v=1 protocol=<selected> ...`  
- 若选择 binary：客户端发送 `UPGRADE BINARY` 后进入严格二进制帧通道
- 握手关键字匹配需避免 Locale 相关大小写陷阱（使用 locale-independent 的 ignoreCase 匹配）

### Requirement: 资源治理与 DoS 防护（连接/行长度/超时）
**Module:** command
将配置项真正落地到运行时行为，避免“配置存在但不生效”的假象。

#### Scenario: 超限连接被拒绝
前置：设置 `server.max.connections`  
- 超出上限的新连接被拒绝并快速关闭  
- 现有连接不受影响

#### Scenario: 文本协议超长输入被拒绝
前置：设置 `protocol.text.max.line.bytes`  
- 读入阶段即拒绝超长单行输入，避免 OOM/CPU 过载

#### Scenario: 长耗时命令按 timeout 中断
前置：设置 `performance.command.timeout`  
- 命令执行超过超时阈值返回错误  
- 不长期占用 worker 线程

### Requirement: 缓存隔离与敏感命令策略（防串线/防泄露）
**Module:** command
命令缓存必须显式考虑“上下文维度”（client/session），避免把会话/身份信息缓存为“公共结果”。

补充约束：缓存开关以 `CommandMeta.cacheable` + `CommandPipeline` 为唯一入口（SSOT），命令实现不应自行调用 `PerformanceOptimizer.getCachedResult`，避免“全局缓存/会话缓存混用”的语义漂移。

#### Scenario: 缓存 key 含 clientId，避免跨客户端串线
前置：命令被标记为可缓存  
- 缓存 key 至少包含 `commandName + args + clientId`
- 即使误把敏感命令标记为可缓存，也不会导致不同客户端拿到彼此的输出

#### Scenario: session 默认不缓存且 token 脱敏
前置：执行 `session`  
- `session` 命令不可缓存
- `SessionId` 默认脱敏输出
- 仅在显式参数 `session --show-token` 时输出完整 token（敏感信息）

### Requirement: 插件化命令与分帧协议
**Module:** command
支持插件加载与分帧/流式输出，保持兼容模式；并支持严格二进制帧通道。

#### Scenario: 插件命令加载
前置：CommandProcessor 启动  
- SPI/插件目录加载
- 插件目录加载需显式开启 `plugins.enabled=true`（默认关闭）
- classpath 的 `ServiceLoader` provider 需显式开启 `plugins.serviceloader.enabled=true`（默认关闭），避免在“插件默认禁用”预期下仍加载目标进程 classpath 上的 provider
- 支持 `plugins.allowlist.sha256`（可选）：不在 allowlist 或 sha256 不匹配的 jar 会被拒绝并记录审计
- 冲突策略可配置（prefer-builtin / prefer-plugin / fail）
- 插件命令注册必须提供 CommandMeta（否则拒绝注册），避免插件绕过权限策略或产生“行为漂移”
- shutdown 时关闭插件 URLClassLoader，降低 Windows JAR 锁定与句柄泄露风险

#### Scenario: 分帧与流式输出
前置：客户端使用 framed 模式  
- DATA/END/ERR 分帧输出
- watch/trace/monitor/tt/stack 可流式推送
- **流式命令同样走 `CommandPipeline`**：统一 executor/timeout/impact permit，并对每个输出 chunk 执行 `InputValidator.sanitizeOutput`（脱控制字符 + 截断）
- legacy 文本协议的流式输出会追加单行 END marker（`protocol.text.end.marker.enabled=true`），降低客户端“超时猜结束”导致的截断风险

实现要点：
- 连接协议与回写逻辑：`CommandProcessor` → `CommandClientHandler`（连接生命周期） → `com.javasleuth.command.server.protocol.*`（text/framed/binary handlers + shared `CommandRequestExecutor`）

### Requirement: 高风险命令二次确认（防误触 + 可审计）
**Module:** command / security
高风险命令默认启用一次性 token 二次确认，降低误触/脚本误用风险。

#### Scenario: redefine/heapdump 等需确认 token
前置：命令 meta 标记 dangerous  
- 第一次执行：返回 challenge token（不执行）
- 第二次执行：追加 `--confirm <token>` 且 token 校验通过才执行
- `--confirm` 参数会在执行前从 args 中剥离，避免影响原命令参数解析

#### Scenario: impact=HIGH 命令治理（确认 + 并发限制）
前置：命令 meta 标记 `impact=HIGH`（例如 heapdump/redefine/retransform/mc/reset/stop/jad/dump）  
- 默认同样需要 `--confirm <token>`（可通过 `security.impact.high.confirm.enabled` 关闭）
- 默认同一时刻仅允许有限并发（`security.impact.high.concurrent.limit`，默认 1），避免多个重型操作叠加导致停顿/峰值

### Requirement: Arthas-like 命令集（简化版）
**Module:** command
对齐 Arthas 高频能力，保持“本机诊断 + 受控输入”。

#### Scenario: 常用命令覆盖
- watch/trace：支持 `--expr/--condition` 与 `--bg`（配合 jobs），多 ClassLoader 场景可用 `--loader <id>` 指定目标 ClassLoader（避免同名类不确定）
- monitor：周期统计输出（支持 `--bg`）
- tt（lite）：record/list/detail/replay（replay 仅生成模板，不在目标 JVM 执行；支持 `--bg`）
- stack：新增 `stack <class-pattern> <method-pattern>` 方法触发调用栈追踪（支持 `-n/-t/--depth/--bg`）；保留原 `stack monitor/dump/analyze/...` 线程栈采样分析
- jobs：list/tail/stop 管理后台任务
- reset：一键清理增强与会话并回滚 retransform
- stop/session/perm/version/logger/dump/getstatic/vmoption：诊断与管理补齐
- vmtool（lite）：实例追踪（track/instances/inspect）+ 受控方法调用（invoke/invoke-static，需二次确认）+ histogram（HotSpot best-effort）
- `StackCommand`/`TtCommand` 子模块化：解析/会话/执行/格式化下沉到 `com.javasleuth.command.impl.stack.*` 与 `com.javasleuth.command.impl.tt.*`，主命令仅保留 subcommand 分发与 jobs 提交

#### Scenario: 严格二进制帧输出
前置：握手选择 binary 模式  
- REQUEST/DATA/ERR/END 二进制帧读写
- Payload 支持任意换行与长输出（长度前缀）

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
- security
- monitoring
- util
- enhancement

## Change History
- 202601281100_init_kb (planned)
- 202601281207_sleuth_plugin_stream (history/2026-01/202601281207_sleuth_plugin_stream/) - 插件化命令与分帧协议
- 202601281301_sleuth_handshake_secure_frames (history/2026-01/202601281301_sleuth_handshake_secure_frames/) - 握手协商 + 严格二进制帧 + 插件授权治理
- 202601291031_fix-5-issues (history/2026-01/202601291031_fix-5-issues/) - 统一传输层/握手升级重构、连接/行长度/超时治理
- 202602011222_sleuth_hardening_bootstrap (history/2026-02/202602011222_sleuth_hardening_bootstrap/) - 插件默认关闭 + allowlist + classloader 释放
- 202602011706_core_fixes_java8_jad_session_regex_trace (history/2026-02/202602011706_core_fixes_java8_jad_session_regex_trace/) - 缓存隔离、session 脱敏与诊断命令稳定性加固
- 202602021233_quality_audit_more_issues (history/2026-02/202602021233_quality_audit_more_issues/) - 协议上限/危险命令元信息与关键边界单测补齐
- 202602041158_unified_exec_pipeline (history/2026-02/202602041158_unified_exec_pipeline/) - 流式命令纳入 Pipeline、legacy END marker、后台 jobs 并发上限与多 ClassLoader 选类回滚一致性
- 202602042257_vmtool_instance_diagnostics (history/2026-02/202602042257_vmtool_instance_diagnostics/) - vmtool（lite）：实例追踪/检视/受控调用
- 202602051031_command_pipeline_step_chain (history/2026-02/202602051031_command_pipeline_step_chain/) - CommandPipeline Step/Interceptor 链重构 + CommandProcessor 拆分
- 202602051334_giant_files_split_handlers_stack_tt (history/2026-02/202602051334_giant_files_split_handlers_stack_tt/) - 继续压小巨型文件：协议 handler 拆分 + Stack/TT 子模块化
- 202602051436_command_args_validation_logging (history/2026-02/202602051436_command_args_validation_logging/) - 参数解析/异常处理/Locale 归一化加固（避免坏输入中断与吞异常黑洞）
