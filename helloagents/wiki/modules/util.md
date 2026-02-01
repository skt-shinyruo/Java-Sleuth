# util

## Purpose
性能优化、缓存、内存优化等工具能力。

## Module Overview
- **Responsibility:** PerformanceOptimizer/MemoryOptimizer/JvmUtils + 诊断辅助工具
- **Status:** ✅Stable
- **Last Updated:** 2026-02-01

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
提供不引入第三方框架的轻量日志封装，支持通过 `logging.level` 控制输出等级。

#### Scenario: INFO 不刷屏，DEBUG 才输出增强细节
前置：运行时设置 `logging.level`  
- INFO/WARN/ERROR：仅输出关键事件  
- DEBUG/TRACE：输出更详细的增强/调试信息（包含异常栈）

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
- config

## Utilities Added
- WildcardMatcher：统一 `*` 通配符匹配（避免将用户输入当作正则）
- RingBuffer：jobs/tt 等能力复用的环形缓冲
- SleuthValueFormatter：安全可读化（限深/限长/脱敏）
- SleuthValueSnapshotter / SleuthSnapshotValue：采集阶段“值快照”（避免 watch/tt 强引用复杂对象图导致内存压力）
- SleuthConditionEvaluator：受控条件过滤（lhs:op:rhs，支持 cost 单位）
- StringUtils：Java 8 兼容字符串工具（替代 `String.repeat`）
- ReflectionUtils：Java 8 兼容反射访问判断（替代 `Field.canAccess`）
- CfrDecompiler：CFR 反编译封装（将 `.class` bytecode 可靠喂给 CFR，避免空输出）

## Change History
- 202601281100_init_kb (planned)
- 202601291031_fix-5-issues (history/2026-01/202601291031_fix-5-issues/) - maintenance GC 默认关闭、引入 SleuthLogger
- 202602011706_core_fixes_java8_jad_session_regex_trace (history/2026-02/202602011706_core_fixes_java8_jad_session_regex_trace/) - Java 8 兼容 + jad/session/regex/trace/watch/tt 稳定性与安全加固
