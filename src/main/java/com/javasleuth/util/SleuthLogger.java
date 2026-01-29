package com.javasleuth.util;

import com.javasleuth.config.ProductionConfig;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Lightweight logger with runtime-configurable level.
 *
 * <p>Designed for agent-side usage where introducing a logging framework is undesirable.</p>
 */
public final class SleuthLogger {
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

    public static void debug(String message) {
        log(Level.DEBUG, message, null);
    }

    public static void info(String message) {
        log(Level.INFO, message, null);
    }

    public static void warn(String message) {
        log(Level.WARN, message, null);
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

        String ts = TIMESTAMP_FORMATTER.format(Instant.now());
        String safeMessage = message == null ? "" : message.replaceAll("[\r\n\t]", " ");
        String line = String.format("[%s] [%s] %s", ts, level.name(), safeMessage);

        PrintStream out = level.ordinal() >= Level.WARN.ordinal() ? System.err : System.out;
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
        String v = value.trim().toUpperCase();
        if (v.isEmpty()) {
            return fallback;
        }
        try {
            return Level.valueOf(v);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
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

