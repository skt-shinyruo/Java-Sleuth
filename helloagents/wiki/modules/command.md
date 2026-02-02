# command

## Purpose
定义命令接口、注册与执行链路。

## Module Overview
- **Responsibility:** 命令解析、校验、执行、输出
- **Status:** ✅Stable
- **Last Updated:** 2026-02-02

## Specifications

### Requirement: 命令执行链路
**Module:** command
从输入到执行的完整处理流程。

#### Scenario: 命令被解析并执行
前置：客户端已连接  
- 输入校验
- 命令执行
- 结果清洗与返回

### Requirement: 统一传输层与协议状态机（握手/升级）
**Module:** command / protocol
服务端与客户端在同一底层 `InputStream/OutputStream` 上完成文本行协议与二进制帧协议的协商与升级，避免混用多层缓冲导致的边界错位。

#### Scenario: HELLO/CONFIG 协商并升级到 binary
前置：`protocol.handshake.enabled=true`  
- 客户端发送 `HELLO ... protocols=legacy,framed,binary`  
- 服务端返回 `CONFIG v=1 protocol=<selected> ...`  
- 若选择 binary：客户端发送 `UPGRADE BINARY` 后进入严格二进制帧通道

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
- 支持 `plugins.allowlist.sha256`（可选）：不在 allowlist 或 sha256 不匹配的 jar 会被拒绝并记录审计
- 冲突策略可配置（prefer-builtin / prefer-plugin / fail）
- 插件命令动态注册到 AuthorizationManager（避免 unknown command 被拒绝）
 - shutdown 时关闭插件 URLClassLoader，降低 Windows JAR 锁定与句柄泄露风险

#### Scenario: 分帧与流式输出
前置：客户端使用 framed 模式  
- DATA/END/ERR 分帧输出
- watch/trace/monitor/tt 可流式推送

### Requirement: Arthas-like 命令集（简化版）
**Module:** command
对齐 Arthas 高频能力，保持“本机诊断 + 受控输入”。

#### Scenario: 常用命令覆盖
- watch/trace：支持 `--expr/--condition` 与 `--bg`（配合 jobs）
- monitor：周期统计输出（支持 `--bg`）
- tt（lite）：record/list/detail/replay（replay 仅生成模板，不在目标 JVM 执行；支持 `--bg`）
- stack：新增 `stack <class-pattern> <method-pattern>` 方法触发调用栈追踪（支持 `-n/-t/--depth/--bg`）；保留原 `stack monitor/dump/analyze/...` 线程栈采样分析
- jobs：list/tail/stop 管理后台任务
- reset：一键清理增强与会话并回滚 retransform
- stop/session/perm/version/logger/dump/getstatic/vmoption：诊断与管理补齐

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
