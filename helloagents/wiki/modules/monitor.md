# monitor

## Purpose
拦截方法调用并输出监控事件。

## Module Overview
- **Responsibility:** Watch/Trace/Monitor/TT 拦截器、事件分发与聚合
- **Status:** ✅Stable
- **Last Updated:** 2026-02-20
- **Build Module:** bootstrap（bootstrap 可见桥接层）

## Specifications

### Requirement: 监控事件分发
**Module:** monitor
将插桩事件写入队列供命令读取。

#### Scenario: 事件入队
前置：增强方法被调用  
- 构造 Result
- 入队并避免影响业务线程

### Requirement: 背压与采样
**Module:** monitor
高频事件时限制队列并支持丢弃/采样。

#### Scenario: 高负载事件流
前置：方法高频触发  
- 队列上限生效
- 采样率可配置

### Requirement: Trace 采样一致性（调用级）
**Module:** monitor
避免“entry 被丢弃但 exit 被保留”的不配对问题，保证调用树/层级稳定。

#### Scenario: entry/exit/subcall 采样一致
前置：trace 已开启且存在采样  
- 每次方法调用在 entry 时做一次采样决定并写入 ThreadLocal 栈
- 同一次调用的 exit/subcall 复用该决定，保证配对与 depth 一致
- `trace --sample <rate>` 可对单次 trace 会话覆盖采样率

### Requirement: Monitor 独立采样 key
**Module:** monitor / config
避免 trace 与 monitor 共用采样 key 导致误调。

#### Scenario: monitor.sample.rate 与 trace.sample.rate 解耦
前置：需要单独调节 monitor 精度/开销  
- trace 使用 `monitoring.trace.sample.rate`（默认更保守）
- monitor 使用 `monitoring.monitor.sample.rate`（默认 1.0）

## API Interfaces
N/A

## Data Models
- WatchResult
- TraceResult
- TtRecord
- StackTraceResult

## Notes
- TraceAggregator 在命令侧将 TraceResult 聚合为“单次调用树”（简化版），便于快速定位耗时链路。
- TraceAggregator 达到 `maxNodes` 上限后会进入“丢弃模式”：后续 METHOD_ENTRY 不再创建节点，但会记录丢弃深度以保证 onExit 不会误 pop 真实节点，避免调用栈失配导致树结构错乱/提前完成。
- StackInterceptor 以“方法触发点”采集调用栈并写入队列，使用与 watch/tt 相同的队列容量与满队列策略（drop/evict），避免影响业务线程。
- Watch/Tt 事件在采集阶段会对参数/返回值/异常做“值快照”（限深/限长），避免强引用复杂对象图造成 GC 压力或 OOM。
- watch 的 `--no-params/--no-return` 仅控制值采集，不再抑制 METHOD_ENTRY/METHOD_EXIT 事件；未采集时通过 `parametersCaptured/returnCaptured=false` 标记，并在输出中显示 `<not captured>` 占位。
- 拦截器侧的背压/采样/丢弃策略读取 `sleuth.monitoring.*` sysprop（带默认值）；core 在启动阶段会 best-effort 将 `ProductionConfig` 的 effective 值同步到 sysprop（未显式覆盖时补齐），保证“配置文件语义”与 bootstrap 拦截器一致。
- TraceEnhancer 对“同一类内可被 trace 的方法调用”跳过 SUB_METHOD_CALL 注入，避免出现 SUB + 子节点双份记录导致的语义重复。
- TraceInterceptor 的 ThreadLocal 状态在 map 为空时会 remove，降低线程池场景的潜在残留与固定开销。
- 断连资源治理：监控类命令会注册到 ClientSession 的清理动作；当连接写失败/断连时会尽快回收增强与队列，避免“误触后后台持续开销”。
- VmToolInterceptor：用于 vmtool track 的实例追踪（弱引用 + 有界缓存），由构造器插桩触发回调；仅覆盖“启用 track 后新创建”的对象，不遍历全堆实例。

## Dependencies
- data

## Change History
- 202601281100_init_kb (planned)
- 202601281207_sleuth_plugin_stream (history/2026-01/202601281207_sleuth_plugin_stream/) - 背压与采样策略
- 202602011222_sleuth_hardening_bootstrap (history/2026-02/202602011222_sleuth_hardening_bootstrap/) - Trace 调用级采样一致性与默认采样调整
- 202602011706_core_fixes_java8_jad_session_regex_trace (history/2026-02/202602011706_core_fixes_java8_jad_session_regex_trace/) - watch/tt 值快照与 trace 语义去重
- 202602042257_vmtool_instance_diagnostics (history/2026-02/202602042257_vmtool_instance_diagnostics/) - VmToolInterceptor（实例追踪）
- 202602132045_bootstrap_boundary_cleanup (history/2026-02/202602132045_bootstrap_boundary_cleanup/) - bootstrap 边界收敛（拦截器/数据模型/值快照下沉到 bootstrap）
- 202602200937_watch_no_params_no_return_semantics (history/2026-02/202602200937_watch_no_params_no_return_semantics/) - watch `--no-params/--no-return` 语义对齐（仅禁用值采集，不再抑制 entry/exit 事件）
