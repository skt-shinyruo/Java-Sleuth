package com.javasleuth.bootstrap.monitor;

/**
 * Minimal configuration reader for bootstrap-visible interceptors.
 *
 * <p>Design goal: avoid depending on full config/security stacks in bootstrap.
 * Interceptors run on application threads, so lookups must be cheap and side-effect free.</p>
 *
 * <p>All keys use the {@code sleuth.} sysprop namespace to match {@code ProductionConfig} conventions.</p>
 */
final class BootstrapMonitorConfig {
    private static final String PREFIX = "sleuth.";

    private BootstrapMonitorConfig() {}

    static boolean isWatchDropOnFull() {
        Boolean fromStore = BootstrapMonitorConfigStore.getWatchDropOnFull();
        if (fromStore != null) {
            return fromStore.booleanValue();
        }
        return getBoolean(PREFIX + "monitoring.watch.drop.on.full", true);
    }

    static boolean isTraceDropOnFull() {
        Boolean fromStore = BootstrapMonitorConfigStore.getTraceDropOnFull();
        if (fromStore != null) {
            return fromStore.booleanValue();
        }
        return getBoolean(PREFIX + "monitoring.trace.drop.on.full", true);
    }

    static double getTraceSampleRate() {
        Double fromStore = BootstrapMonitorConfigStore.getTraceSampleRate();
        if (fromStore != null) {
            return clamp01(fromStore.doubleValue());
        }
        return clamp01(getDouble(PREFIX + "monitoring.trace.sample.rate", 0.1d));
    }

    static double getMonitorSampleRate() {
        Double fromStore = BootstrapMonitorConfigStore.getMonitorSampleRate();
        if (fromStore != null) {
            return clamp01(fromStore.doubleValue());
        }
        return clamp01(getDouble(PREFIX + "monitoring.monitor.sample.rate", 1.0d));
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        String v = System.getProperty(key);
        if (v == null) {
            return defaultValue;
        }
        String s = v.trim().toLowerCase();
        if (s.isEmpty()) {
            return defaultValue;
        }
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s) || "on".equals(s);
    }

    private static double getDouble(String key, double defaultValue) {
        String v = System.getProperty(key);
        if (v == null) {
            return defaultValue;
        }
        String s = v.trim();
        if (s.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(s);
        } catch (Exception ignore) {
            return defaultValue;
        }
    }

    private static double clamp01(double v) {
        if (v < 0.0d) {
            return 0.0d;
        }
        if (v > 1.0d) {
            return 1.0d;
        }
        return v;
    }
}
