# util

## Purpose
性能优化、缓存、内存优化等工具能力。

## Module Overview
- **Responsibility:** PerformanceOptimizer/MemoryOptimizer/JvmUtils + 诊断辅助工具
- **Status:** ✅Stable
- **Last Updated:** 2026-02-13
- **Build Module:** foundation + bootstrap（bootstrap-bridge）+ core（少量 core 专用 util）

## Specifications

### Requirement: 性能与内存优化辅助
**Module:** util
提供缓存、指标、GC 建议与 JVM 工具能力。

#### Scenario: 缓存命令结果
前置：命令可缓存  
- 命中缓存直接返回
- 过期后重新计算

### Requirement: 维护策略可配置（默认不强制 System.gc）
**Module:** util
避免默认定时触发 `System.gc()` 造成 STW 抖动，将强制 GC 变更为显式开关。

#### Scenario: 默认不触发强制 GC，可配置开启
前置：`performance.maintenance.force_gc=false`（默认）  
- maintenance 不会主动调用 `System.gc()`  
- 如确需启用，可显式设置为 true 并记录相关审计/提示

### Requirement: 轻量日志封装（agent 侧降噪）
**Module:** util
提供不引入第三方框架的轻量日志封装，统一系统日志格式/等级/上下文字段，支持通过 `logging.level` 控制输出等级，并可通过 `logging.console.enabled` 控制是否输出到控制台（stderr）。

#### Scenario: 系统日志与用户输出分层（stderr vs stdout/协议输出）
前置：默认配置  
- `SleuthLogger` 统一输出前缀 `SLEUTH:`，并将系统日志写入 stderr（避免污染 stdout 与协议/用户输出混杂）
- 审计日志（AuditLogger）独立通道，默认不镜像到控制台（避免污染目标 JVM 输出）

#### Scenario: Throwable 输出可控（禁止 printStackTrace）
前置：异常路径触发 `SleuthLogger.*(msg, throwable)`  
- `SleuthLogger` 不使用 `Throwable.printStackTrace()`，改为受控格式化输出（可限制最大栈帧数，避免极端情况下刷屏）
- ERROR 级别默认输出诊断堆栈（用于快速定位）；WARN/INFO 等级在未开启 DEBUG 时尽量保持简洁

#### Scenario: 审计控制台镜像独立于 logging.level
前置：`logging.audit.console.enabled=true`  
- 审计控制台镜像以 `logging.audit.console.enabled` 为唯一开关（SSOT），不依赖 `logging.level`/`logging.console.enabled` 的组合语义
- 通过 `SleuthLogger.auditConsole(Level, msg)` 写入 stderr，避免污染 stdout

#### Scenario: 统一上下文字段（clientId/sessionId/connId/command）
前置：命令执行链路已建立 `CommandContext`  
- `SleuthLogger` 仅从 `SleuthLogContext`（ThreadLocal）读取上下文字段（脱敏 sessionId/connId），便于线上聚合与检索
- `command` 层在执行入口/链路中负责写入 `SleuthLogContext` 并在请求结束时 clear（避免线程池复用导致泄露）
- `jobs`（后台任务）在提交时捕获触发命令的上下文，并在 Job 线程执行前写入、执行后 clear（避免线程池复用导致串号）

#### Scenario: 单测默认降噪
前置：`mvn test`  
- Surefire 默认设置 `-Dsleuth.logging.level=ERROR`，避免 INFO/WARN 日志刷屏淹没断言失败
- 如需排查问题可临时提高：`-Dsleuth.logging.level=DEBUG`

### Requirement: 线程治理基础设施（命名/daemon/异常处理/关闭策略）
**Module:** util
将线程与生命周期治理作为“一等概念”统一收敛，降低目标 JVM 内后台线程残留与排障成本。

#### Scenario: 统一 ThreadFactory 与有界 shutdown
前置：组件需要后台线程或线程池  
- 统一使用 `SleuthThreadFactory` 创建 daemon 线程（统一命名 + UncaughtExceptionHandler）
- 统一使用 `SleuthExecutors.shutdownAndAwait(...)` 做有界等待，避免 shutdown hang

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
- config

## Utilities Added
- JarLocator（bootstrap）：Agent/Core/Launcher 产物定位 SSOT（Manifest marker + override + 常见目录扫描 + CodeSource 自身 jar 定位）
- AgentArgsApplier（bootstrap）：`agentArgs` 解析与 sysprop 落地 SSOT（bootstrap/core 共用）
- RingBuffer（bootstrap）：jobs/tt 等能力复用的环形缓冲
- SleuthValueFormatter（bootstrap）：安全可读化（限深/限长/脱敏）
- SleuthValueSnapshotter / SleuthSnapshotValue（bootstrap）：采集阶段“值快照”（避免 watch/tt 强引用复杂对象图导致内存压力）
- WildcardMatcher（foundation）：统一 `*` 通配符匹配（避免将用户输入当作正则）
- LoadedClassResolver（foundation）：已加载类解析/选择（输出候选 + loaderId），用于多 ClassLoader 场景稳定选择目标类并避免回滚错对象
- SleuthLogContext（foundation）：ThreadLocal 日志上下文（由上层写入，util 侧只读）
- StringUtils（foundation）：Java 8 兼容字符串工具（替代 `String.repeat`），并补充 `isBlank`
- ReflectionUtils（foundation）：Java 8 兼容反射访问判断（替代 `Field.canAccess`）
- SleuthObjectInspector（core）：对象字段检视（best-effort，仅字段读取，限深/限长/脱敏）
- CfrDecompiler（core）：CFR 反编译封装（将 `.class` bytecode 可靠喂给 CFR，避免空输出）

## Change History
- 202601281100_init_kb (planned)
- 202601291031_fix-5-issues (history/2026-01/202601291031_fix-5-issues/) - maintenance GC 默认关闭、引入 SleuthLogger
- 202602011706_core_fixes_java8_jad_session_regex_trace (history/2026-02/202602011706_core_fixes_java8_jad_session_regex_trace/) - Java 8 兼容 + jad/session/regex/trace/watch/tt 稳定性与安全加固
- 202602041158_unified_exec_pipeline (history/2026-02/202602041158_unified_exec_pipeline/) - LoadedClassResolver（多 ClassLoader 选类/回滚稳定性）
- 202602042257_vmtool_instance_diagnostics (history/2026-02/202602042257_vmtool_instance_diagnostics/) - SleuthObjectInspector（对象字段检视）
- 202602051743_exception_handling_logging (history/2026-02/202602051743_exception_handling_logging/) - SleuthLogger throwable 可控格式化与审计控制台镜像语义收敛
- 202602101815_layering_modularization (history/2026-02/202602101815_layering_modularization/) - 分层边界恢复：util 下沉到 foundation + 通过 SleuthLogContext 解耦 command 依赖
- 202602132045_bootstrap_boundary_cleanup (history/2026-02/202602132045_bootstrap_boundary_cleanup/) - 值快照/环形缓冲/JarLocator/agentArgs 落地收敛到 bootstrap，SleuthObjectInspector 迁移到 core
- 202602132323_arch_boundary_lifecycle_singletons (history/2026-02/202602132323_arch_boundary_lifecycle_singletons/) - JarLocator CodeSource 自身 jar 定位、命令 close 纳管、vmtool/profiler shutdown 闭环
