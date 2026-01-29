package com.javasleuth.data;

import java.io.Serializable;

/**
 * StackTraceResult 表示一次方法触发时的调用栈快照（简化版）。
 *
 * <p>设计目标：尽量不影响业务线程；当采集失败时应静默降级。</p>
 */
public class StackTraceResult implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum EventType {
        METHOD_ENTRY
    }

    private String stackId;
    private String className;
    private String methodName;
    private String methodDescriptor;
    private long timestampMs;
    private EventType eventType;
    private String threadName;
    private long threadId;
    private StackTraceElement[] stackTrace;

    public String getStackId() { return stackId; }
    public void setStackId(String stackId) { this.stackId = stackId; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getMethodDescriptor() { return methodDescriptor; }
    public void setMethodDescriptor(String methodDescriptor) { this.methodDescriptor = methodDescriptor; }

    public long getTimestampMs() { return timestampMs; }
    public void setTimestampMs(long timestampMs) { this.timestampMs = timestampMs; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }

    public long getThreadId() { return threadId; }
    public void setThreadId(long threadId) { this.threadId = threadId; }

    public StackTraceElement[] getStackTrace() { return stackTrace; }
    public void setStackTrace(StackTraceElement[] stackTrace) { this.stackTrace = stackTrace; }
}

