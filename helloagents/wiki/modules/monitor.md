# monitor

## Purpose
拦截方法调用并输出监控事件。

## Module Overview
- **Responsibility:** Watch/Trace/Monitor/TT 拦截器、事件分发与聚合
- **Status:** ✅Stable
- **Last Updated:** 2026-02-01

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
- StackInterceptor 以“方法触发点”采集调用栈并写入队列，使用与 watch/tt 相同的队列容量与满队列策略（drop/evict），避免影响业务线程。

## Dependencies
- data

## Change History
- 202601281100_init_kb (planned)
- 202601281207_sleuth_plugin_stream (history/2026-01/202601281207_sleuth_plugin_stream/) - 背压与采样策略
- 202602011222_sleuth_hardening_bootstrap (history/2026-02/202602011222_sleuth_hardening_bootstrap/) - Trace 调用级采样一致性与默认采样调整
