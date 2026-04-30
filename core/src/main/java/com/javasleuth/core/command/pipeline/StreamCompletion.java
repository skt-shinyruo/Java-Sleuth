package com.javasleuth.core.command.pipeline;

public final class StreamCompletion {
    public enum Status {
        SUCCESS,
        FAILED,
        CANCELLED
    }

    private final Status status;
    private final Throwable failure;
    private final String message;

    private StreamCompletion(Status status, Throwable failure, String message) {
        this.status = status != null ? status : Status.FAILED;
        this.failure = failure;
        this.message = message;
    }

    static StreamCompletion success() {
        return new StreamCompletion(Status.SUCCESS, null, null);
    }

    static StreamCompletion failed(Throwable failure) {
        return new StreamCompletion(Status.FAILED, failure, messageFor(failure));
    }

    static StreamCompletion cancelled(String reason) {
        return new StreamCompletion(Status.CANCELLED, null, reason);
    }

    public Status getStatus() {
        return status;
    }

    public Throwable getFailure() {
        return failure;
    }

    public String getMessage() {
        return message;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    private static String messageFor(Throwable t) {
        if (t == null) {
            return "Stream command failed";
        }
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getName();
        }
        return message;
    }
}
