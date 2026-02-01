# data

## Purpose
监控结果数据结构定义。

## Module Overview
- **Responsibility:** WatchResult/TraceResult 模型
- **Status:** ✅Stable
- **Last Updated:** 2026-02-01

## Specifications

### Requirement: 监控结果封装
**Module:** data
为监控链路提供统一的数据结构。

#### Scenario: Result 传递
前置：拦截器生成事件  
- 封装为 Result
- 投递到队列

### Requirement: 值快照语义（避免强引用对象图）
**Module:** data / monitor / util
Watch/Tt 结果不直接持有原始参数/返回值/异常对象，默认以“值快照”（摘要字符串/基础类型）形式落入队列与 RingBuffer，降低对目标 JVM 的 GC 与内存压力。

#### Scenario: WatchResult/TtRecord 仅保存快照
前置：watch/tt 事件生成  
- `parameters/returnValue/exception` 字段存放快照值（可能为 `SleuthSnapshotValue`）
- 输出格式化阶段不再触碰原始对象图（避免二次反射遍历与强引用驻留）

## API Interfaces
N/A

## Data Models
- WatchResult
- TraceResult
- TtRecord（TT-lite 录制条目）

## Dependencies
N/A

## Change History
- 202601281100_init_kb (planned)
- 202602011706_core_fixes_java8_jad_session_regex_trace (history/2026-02/202602011706_core_fixes_java8_jad_session_regex_trace/) - watch/tt 值快照语义落地
