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
}
