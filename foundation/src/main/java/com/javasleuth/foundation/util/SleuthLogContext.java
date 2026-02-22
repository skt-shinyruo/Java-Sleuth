package com.javasleuth.foundation.util;

/**
 * Thread-local log context used to enrich {@link SleuthLogger} output with stable correlation fields.
 *
 * <p>Design goals:</p>
 * <ul>
 *   <li>Keep it lightweight (no external logging framework / MDC dependency).</li>
 *   <li>Avoid leaking context across reused threads (caller must clear).</li>
 *   <li>Separate connection-level context from per-command context.</li>
 * </ul>
 */
public final class SleuthLogContext {
    private static final ThreadLocal<SleuthLogContext> CONTEXT = new ThreadLocal<>();

    private String clientId;
    private String sessionId;
    private String connId;
    private String command;

    private SleuthLogContext() {}

    public static void setConnection(String clientId, String sessionId, String connId) {
        SleuthLogContext ctx = CONTEXT.get();
        if (ctx == null) {
            ctx = new SleuthLogContext();
            CONTEXT.set(ctx);
        }
        ctx.clientId = clientId;
        ctx.sessionId = sessionId;
        ctx.connId = connId;
    }

    public static void setCommand(String command) {
        SleuthLogContext ctx = CONTEXT.get();
        if (ctx == null) {
            ctx = new SleuthLogContext();
            CONTEXT.set(ctx);
        }
        ctx.command = command;
    }

    public static SleuthLogContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public String getClientId() {
        return clientId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getConnId() {
        return connId;
    }

    public String getCommand() {
        return command;
    }
}
