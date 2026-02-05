package com.javasleuth.util;

import com.javasleuth.command.CommandContext;
import com.javasleuth.command.CommandContextHolder;
import com.javasleuth.config.ProductionConfig;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Lightweight logger with runtime-configurable level.
 *
 * <p>Designed for agent-side usage where introducing a logging framework is undesirable.</p>
 */
public final class SleuthLogger {
    private static final String SYS_PROP_PREFIX = "sleuth.";
    private static final String SYS_PROP_LOG_LEVEL = SYS_PROP_PREFIX + "logging.level";
    private static final String SYS_PROP_CONSOLE_ENABLED = SYS_PROP_PREFIX + "logging.console.enabled";

    public enum Level {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static volatile ProductionConfig config;

    private SleuthLogger() {}

    public static boolean isEnabled(Level level) {
        return level.ordinal() >= currentLevel().ordinal();
    }

    public static void trace(String message) {
        log(Level.TRACE, message, null);
    }

    public static void trace(String message, Throwable t) {
        log(Level.TRACE, message, t);
    }

    public static void debug(String message) {
        log(Level.DEBUG, message, null);
    }

    public static void debug(String message, Throwable t) {
        log(Level.DEBUG, message, t);
    }

    public static void info(String message) {
        log(Level.INFO, message, null);
    }

    public static void info(String message, Throwable t) {
        log(Level.INFO, message, t);
    }

    public static void warn(String message) {
        log(Level.WARN, message, null);
    }

    public static void warn(String message, Throwable t) {
        log(Level.WARN, message, t);
    }

    public static void error(String message) {
        log(Level.ERROR, message, null);
    }

    public static void error(String message, Throwable t) {
        log(Level.ERROR, message, t);
    }

    private static void log(Level level, String message, Throwable t) {
        if (!isEnabled(level)) {
            return;
        }
        if (!isConsoleEnabled()) {
            return;
        }

        String ts = TIMESTAMP_FORMATTER.format(Instant.now());
        String safeMessage = sanitize(message);
        String ctx = formatContext();

        StringBuilder line = new StringBuilder(64 + safeMessage.length());
        line.append('[').append(ts).append("] [").append(level.name()).append(']');
        if (!ctx.isEmpty()) {
            line.append(' ').append(ctx);
        }
        if (!safeMessage.isEmpty()) {
            line.append(' ').append(safeMessage);
        }

        // System logs should not pollute stdout (stdout is reserved for user-facing outputs / protocol payloads).
        PrintStream out = System.err;
        out.println("SLEUTH: " + line);

        if (t != null) {
            // Only print stack traces when debug logs are enabled to avoid production log spam.
            if (isEnabled(Level.DEBUG)) {
                t.printStackTrace(out);
            } else {
                out.println("SLEUTH: [" + t.getClass().getName() + "] " + String.valueOf(t.getMessage()));
            }
        }
    }

    private static Level currentLevel() {
        Level sysLevel = parseLevel(System.getProperty(SYS_PROP_LOG_LEVEL), null);
        if (sysLevel != null) {
            return sysLevel;
        }
        if (isProductionConfigLoading()) {
            return Level.INFO;
        }
        String configured = null;
        try {
            configured = getConfig().getLoggingLevel();
        } catch (Exception ignore) {
            // ignore
        }
        return parseLevel(configured, Level.INFO);
    }

    private static Level parseLevel(String value, Level fallback) {
        if (value == null) {
            return fallback;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty()) {
            return fallback;
        }
        try {
            return Level.valueOf(v);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static boolean isConsoleEnabled() {
        String sys = System.getProperty(SYS_PROP_CONSOLE_ENABLED);
        if (sys != null) {
            return Boolean.parseBoolean(sys);
        }
        if (isProductionConfigLoading()) {
            return true;
        }
        try {
            return getConfig().getBoolean("logging.console.enabled", true);
        } catch (Exception ignore) {
            return true;
        }
    }

    private static boolean isProductionConfigLoading() {
        try {
            return ProductionConfig.isLoading();
        } catch (Exception ignore) {
            return false;
        }
    }

    private static String formatContext() {
        CommandContext ctx = null;
        try {
            ctx = CommandContextHolder.get();
        } catch (Exception ignore) {
            // ignore
        }

        String clientId = null;
        String sessionId = null;
        String connId = null;
        String command = null;

        if (ctx != null) {
            clientId = ctx.getClientId();
            sessionId = ctx.getSessionId();
            connId = ctx.getConnId();
            command = ctx.getCommandName();
        } else {
            SleuthLogContext tl = SleuthLogContext.get();
            if (tl != null) {
                clientId = tl.getClientId();
                sessionId = tl.getSessionId();
                connId = tl.getConnId();
                command = tl.getCommand();
            }
        }

        StringBuilder sb = new StringBuilder(64);
        appendCtx(sb, "clientId", clientId);
        appendCtx(sb, "sessionId", maskToken(sessionId));
        appendCtx(sb, "connId", maskToken(connId));
        appendCtx(sb, "command", command);

        if (sb.length() == 0) {
            return "";
        }
        return '[' + sb.toString() + ']';
    }

    private static void appendCtx(StringBuilder sb, String key, String value) {
        if (value == null) {
            return;
        }
        String v = sanitize(value).trim();
        if (v.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(key).append('=').append(v);
    }

    private static String maskToken(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() <= 8) {
            return "****";
        }
        return t.substring(0, 4) + "****" + t.substring(t.length() - 4);
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static ProductionConfig getConfig() {
        ProductionConfig c = config;
        if (c == null) {
            c = ProductionConfig.getInstance();
            config = c;
        }
        return c;
    }
}
