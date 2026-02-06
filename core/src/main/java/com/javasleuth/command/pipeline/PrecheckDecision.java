package com.javasleuth.command.pipeline;

public final class PrecheckDecision {
    private final boolean ok;
    private final String error;
    private final String[] args;

    private PrecheckDecision(boolean ok, String error, String[] args) {
        this.ok = ok;
        this.error = error;
        this.args = args;
    }

    public static PrecheckDecision ok(String[] args) {
        return new PrecheckDecision(true, null, args);
    }

    public static PrecheckDecision denied(String error, String[] args) {
        return new PrecheckDecision(false, error, args);
    }

    public boolean isOk() {
        return ok;
    }

    public String getError() {
        return error;
    }

    public String[] getArgs() {
        return args;
    }
}

