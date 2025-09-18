package com.javasleuth.data;

import java.io.Serializable;
import java.util.Arrays;

public class WatchResult implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum EventType {
        METHOD_ENTRY, METHOD_EXIT, METHOD_EXCEPTION
    }

    private String watchId;
    private String className;
    private String methodName;
    private String methodDescriptor;
    private Object[] parameters;
    private Object returnValue;
    private Throwable exception;
    private long startTime;
    private long duration;
    private EventType eventType;
    private String threadName;
    private long threadId;

    // Getters and setters
    public String getWatchId() { return watchId; }
    public void setWatchId(String watchId) { this.watchId = watchId; }

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

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

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

    public String formatParameters() {
        if (parameters == null || parameters.length == 0) {
            return "()";
        }

        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatObject(parameters[i]));
        }
        sb.append(")");
        return sb.toString();
    }

    public String formatReturnValue() {
        return formatObject(returnValue);
    }

    private String formatObject(Object obj) {
        if (obj == null) {
            return "null";
        }

        if (obj instanceof String) {
            return "\"" + obj + "\"";
        }

        if (obj instanceof Number || obj instanceof Boolean || obj instanceof Character) {
            return obj.toString();
        }

        if (obj.getClass().isArray()) {
            if (obj instanceof Object[]) {
                return Arrays.toString((Object[]) obj);
            } else {
                return Arrays.toString((int[]) obj); // Handle primitive arrays
            }
        }

        String className = obj.getClass().getSimpleName();
        return className + "@" + Integer.toHexString(obj.hashCode());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(eventType).append("] ");
        sb.append(className).append(".").append(methodName);

        switch (eventType) {
            case METHOD_ENTRY:
                sb.append(formatParameters());
                break;
            case METHOD_EXIT:
                sb.append(" => ").append(formatReturnValue());
                sb.append(" (").append(formatDuration()).append(")");
                break;
            case METHOD_EXCEPTION:
                sb.append(" threw ").append(exception.getClass().getSimpleName());
                if (exception.getMessage() != null) {
                    sb.append(": ").append(exception.getMessage());
                }
                sb.append(" (").append(formatDuration()).append(")");
                break;
        }

        sb.append(" [").append(threadName).append("]");
        return sb.toString();
    }
}