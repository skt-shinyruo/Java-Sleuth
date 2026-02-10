package com.javasleuth.data;

import java.io.Serializable;

public class TraceResult implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum EventType {
        METHOD_ENTRY, METHOD_EXIT, METHOD_EXCEPTION, SUB_METHOD_CALL
    }

    private String traceId;
    private String className;
    private String methodName;
    private String methodDescriptor;
    private long startTime;
    private long duration;
    private EventType eventType;
    private int depth;
    private String threadName;
    private long threadId;

    // Getters and setters
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getMethodDescriptor() { return methodDescriptor; }
    public void setMethodDescriptor(String methodDescriptor) { this.methodDescriptor = methodDescriptor; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }

    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }

    public long getThreadId() { return threadId; }
    public void setThreadId(long threadId) { this.threadId = threadId; }

    public String formatDuration() {
        if (duration < 1_000) {
            return duration + "ns";
        } else if (duration < 1_000_000) {
            return String.format("%.2fμs", duration / 1_000.0);
        } else if (duration < 1_000_000_000) {
            return String.format("%.2fms", duration / 1_000_000.0);
        } else {
            return String.format("%.2fs", duration / 1_000_000_000.0);
        }
    }

    public String getIndent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getIndent());

        switch (eventType) {
            case METHOD_ENTRY:
                sb.append("├─ ").append(className).append(".").append(methodName).append("()");
                break;
            case METHOD_EXIT:
                sb.append("└─ ").append(className).append(".").append(methodName)
                  .append("() ").append(formatDuration());
                break;
            case METHOD_EXCEPTION:
                sb.append("└─ ").append(className).append(".").append(methodName)
                  .append("() [EXCEPTION] ").append(formatDuration());
                break;
            case SUB_METHOD_CALL:
                sb.append("│  ├─ ").append(className).append(".").append(methodName).append("()");
                break;
        }

        return sb.toString();
    }
}