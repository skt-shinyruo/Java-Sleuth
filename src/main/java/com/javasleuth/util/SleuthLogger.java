package com.javasleuth.util;

import com.javasleuth.command.CommandContext;
import com.javasleuth.command.CommandContextHolder;
import com.javasleuth.config.ProductionConfig;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

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
        log(level, message, t, false, false);
    }

    /**
     * Writes a line to the console regardless of configured logger level and console enablement.
     *
     * <p>Intended for audit console mirroring where the dedicated audit-console switch is the source of truth.</p>
     */
    public static void auditConsole(Level level, String message) {
        Level effective = level != null ? level : Level.INFO;
        log(effective, message, null, true, true);
    }

    public static void auditConsole(Level level, String message, Throwable t) {
        Level effective = level != null ? level : Level.INFO;
        log(effective, message, t, true, true);
    }

    private static void log(Level level, String message, Throwable t, boolean forceEnabled, boolean forceConsole) {
        if (!forceEnabled && !isEnabled(level)) {
            return;
        }
        if (!forceConsole && !isConsoleEnabled()) {
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
            boolean includeStack = level.ordinal() >= Level.ERROR.ordinal() || isEnabled(Level.DEBUG);
            writeThrowable(out, t, includeStack);
        }
    }

    private static void writeThrowable(PrintStream out, Throwable t, boolean includeStack) {
        if (out == null || t == null) {
            return;
        }
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        writeThrowable(out, t, includeStack, seen, 0);
    }

    private static void writeThrowable(PrintStream out,
                                       Throwable t,
                                       boolean includeStack,
                                       Map<Throwable, Boolean> seen,
                                       int depth) {
        if (t == null) {
            return;
        }
        if (seen != null && seen.put(t, Boolean.TRUE) != null) {
            out.println("SLEUTH: [throwable-loop] " + t.getClass().getName());
            return;
        }

        String cls = t.getClass().getName();
        String msg = t.getMessage() != null ? sanitize(t.getMessage()) : "";
        String header = msg.isEmpty() ? cls : (cls + ": " + msg);
        if (depth == 0) {
            out.println("SLEUTH: " + header);
        } else {
            out.println("SLEUTH: Caused by: " + header);
        }

        if (includeStack) {
            final int maxFrames = 80;
            StackTraceElement[] frames = t.getStackTrace();
            int limit = frames != null ? Math.min(frames.length, maxFrames) : 0;
            for (int i = 0; i < limit; i++) {
                out.println("SLEUTH:     at " + frames[i]);
            }
            if (frames != null && frames.length > limit) {
                out.println("SLEUTH:     ... " + (frames.length - limit) + " more");
            }

            Throwable[] suppressed = t.getSuppressed();
            if (suppressed != null && suppressed.length > 0) {
                int supLimit = Math.min(suppressed.length, 5);
                for (int i = 0; i < supLimit; i++) {
                    Throwable s = suppressed[i];
                    if (s == null) {
                        continue;
                    }
                    String sm = s.getMessage() != null ? sanitize(s.getMessage()) : "";
                    if (sm.isEmpty()) {
                        out.println("SLEUTH: Suppressed: " + s.getClass().getName());
                    } else {
                        out.println("SLEUTH: Suppressed: " + s.getClass().getName() + ": " + sm);
                    }
                }
                if (suppressed.length > supLimit) {
                    out.println("SLEUTH: ... " + (suppressed.length - supLimit) + " more suppressed");
                }
            }
        }

        Throwable cause = t.getCause();
        if (cause != null && cause != t && depth < 10) {
            writeThrowable(out, cause, includeStack, seen, depth + 1);
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
