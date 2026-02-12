package com.javasleuth.launcher.client;

/**
 * 单次命令执行结果（用于 headless 统计与 fail-fast）。
 */
public final class CommandResult {
    private final boolean ok;
    private final boolean hadErrorFrame;
    private final String errorMessage;

    private CommandResult(boolean ok, boolean hadErrorFrame, String errorMessage) {
        this.ok = ok;
        this.hadErrorFrame = hadErrorFrame;
        this.errorMessage = errorMessage;
    }

    public static CommandResult ok(boolean hadErrorFrame) {
        return new CommandResult(!hadErrorFrame, hadErrorFrame, null);
    }

    public static CommandResult error(String message) {
        return new CommandResult(false, true, message);
    }

    public boolean isOk() {
        return ok;
    }

    public boolean hadErrorFrame() {
        return hadErrorFrame;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}

