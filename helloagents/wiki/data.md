# Data Models

## Overview
运行时诊断结果通过内存队列传递给命令处理器，再输出到 CLI；主要结构包含 WatchResult 与 TraceResult。

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

