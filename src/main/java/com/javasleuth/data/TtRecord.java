package com.javasleuth.data;

import java.io.Serializable;

public class TtRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum EventType {
        METHOD_EXIT,
        METHOD_EXCEPTION
    }

    private long recordId;
    private String ttId;
    private String className;
    private String methodName;
    private String methodDescriptor;
    private Object[] parameters;
    private Object returnValue;
    private Throwable exception;
    private long startTime;
    private long duration;
    private long timestampMs;
    private EventType eventType;
    private String threadName;
    private long threadId;

    public long getRecordId() { return recordId; }
    public void setRecordId(long recordId) { this.recordId = recordId; }

    public String getTtId() { return ttId; }
    public void setTtId(String ttId) { this.ttId = ttId; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getMethodDescriptor() { return methodDescriptor; }
    public void setMethodDescriptor(String methodDescriptor) { this.methodDescriptor = methodDescriptor; }

    public Object[] getParameters() { return parameters; }
    public void setParameters(Object[] parameters) { this.parameters = parameters; }

    public Object getReturnValue() { return returnValue; }
    public void setReturnValue(Object returnValue) { this.returnValue = returnValue; }

    public Throwable getException() { return exception; }
    public void setException(Throwable exception) { this.exception = exception; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }

    public long getTimestampMs() { return timestampMs; }
    public void setTimestampMs(long timestampMs) { this.timestampMs = timestampMs; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }

    public long getThreadId() { return threadId; }
    public void setThreadId(long threadId) { this.threadId = threadId; }
}

