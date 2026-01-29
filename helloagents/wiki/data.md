# Data Models

## Overview
运行时诊断结果通过内存队列传递给命令处理器，再输出到 CLI；核心结构包含 WatchResult、TraceResult，并扩展了 TT-lite 的 TtRecord。

---

## Data Tables/Collections

### WatchResult

**Description:** 方法监控事件（入口/退出/异常）

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| watchId | String | - | Watch 会话 ID |
| className | String | - | 类名 |
| methodName | String | - | 方法名 |
| methodDescriptor | String | - | 方法签名 |
| parameters | Object[] | Nullable | 入口参数 |
| returnValue | Object | Nullable | 返回值 |
| exception | Throwable | Nullable | 异常 |
| startTime | long | - | 纳秒时间 |
| duration | long | - | 执行耗时 |
| eventType | enum | - | ENTRY/EXIT/EXCEPTION |
| threadName | String | - | 线程名 |
| threadId | long | - | 线程 ID |

### TraceResult

**Description:** 调用链事件（入口/退出/异常/子调用）

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| traceId | String | - | Trace 会话 ID |
| className | String | - | 类名 |
| methodName | String | - | 方法名 |
| methodDescriptor | String | - | 方法签名 |
| startTime | long | - | 纳秒时间 |
| duration | long | - | 执行耗时 |
| eventType | enum | - | ENTRY/EXIT/EXCEPTION/SUB |
| depth | int | - | 调用深度 |
| threadName | String | - | 线程名 |
| threadId | long | - | 线程 ID |

### TtRecord

**Description:** TT-lite 录制条目（退出/异常）

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| recordId | long | - | 记录 ID（自增） |
| ttId | String | - | TT 会话 ID |
| className | String | - | 类名 |
| methodName | String | - | 方法名 |
| methodDescriptor | String | - | 方法签名 |
| parameters | Object[] | Nullable | 入参快照（受限格式化输出） |
| returnValue | Object | Nullable | 返回值快照（受限格式化输出） |
| exception | Throwable | Nullable | 异常（仅摘要输出） |
| startTime | long | - | 纳秒时间 |
| duration | long | - | 执行耗时 |
| timestampMs | long | - | 事件时间戳（ms） |
| eventType | enum | - | EXIT/EXCEPTION |
| threadName | String | - | 线程名 |
| threadId | long | - | 线程 ID |

### StackTraceResult

**Description:** 方法触发时的调用栈快照（简化版，入口事件）

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| stackId | String | - | Stack 会话 ID |
| className | String | - | 类名 |
| methodName | String | - | 方法名 |
| methodDescriptor | String | - | 方法签名 |
| timestampMs | long | - | 事件时间戳（ms） |
| eventType | enum | - | ENTRY |
| threadName | String | - | 线程名 |
| threadId | long | - | 线程 ID |
| stackTrace | StackTraceElement[] | Nullable | 调用栈帧（受 depth 限制，可能被过滤） |
