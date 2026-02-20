package com.javasleuth.data;

import java.io.Serializable;
import com.javasleuth.util.SleuthValueFormatter;

public class WatchResult implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final String NOT_CAPTURED = "<not captured>";
    private static final SleuthValueFormatter.Options FORMAT_OPTIONS =
        new SleuthValueFormatter.Options()
            .withMaxDepth(2)
            .withMaxStringLength(200)
            .withMaxCollectionItems(20)
            .withMaxMapEntries(20);

    public enum EventType {
        METHOD_ENTRY, METHOD_EXIT, METHOD_EXCEPTION
    }

    private String watchId;
    private String className;
    private String methodName;
    private String methodDescriptor;
    private Object[] parameters;
    // Default to true for backward-compatibility: old producers didn't set these flags.
    private boolean parametersCaptured = true;
    private Object returnValue;
    // Default to true for backward-compatibility: old producers didn't set these flags.
    private boolean returnCaptured = true;
    private Object exception;
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

    public boolean isParametersCaptured() { return parametersCaptured; }
    public void setParametersCaptured(boolean parametersCaptured) { this.parametersCaptured = parametersCaptured; }

    public Object getReturnValue() { return returnValue; }
    public void setReturnValue(Object returnValue) { this.returnValue = returnValue; }

    public boolean isReturnCaptured() { return returnCaptured; }
    public void setReturnCaptured(boolean returnCaptured) { this.returnCaptured = returnCaptured; }

    public Object getException() { return exception; }
    public void setException(Object exception) { this.exception = exception; }

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
        if (!parametersCaptured) {
            return "(" + NOT_CAPTURED + ")";
        }
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
        if (!returnCaptured) {
            return NOT_CAPTURED;
        }
        return formatObject(returnValue);
    }

    private String formatObject(Object obj) {
        return SleuthValueFormatter.format(obj, FORMAT_OPTIONS);
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
                sb.append(" threw ").append(formatObject(exception));
                sb.append(" (").append(formatDuration()).append(")");
                break;
        }

        sb.append(" [").append(threadName).append("]");
        return sb.toString();
    }
}
