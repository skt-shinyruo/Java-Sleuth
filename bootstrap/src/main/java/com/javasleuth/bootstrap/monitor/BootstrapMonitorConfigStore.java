package com.javasleuth.bootstrap.monitor;

/**
 * Bootstrap 可见的监控配置存储（JDK-only）。
 *
 * <p>目的：避免在 bootstrap 拦截器中频繁读取/解析 System properties，并把“派生配置”
 * 与“外部覆盖（sysprop）”解耦，减少跨 attach 的全局状态残留风险。</p>
 *
 * <p>注意：该 Store 是 attach 级别的临时状态，必须在 detach/shutdown 时清理。</p>
 */
public final class BootstrapMonitorConfigStore {
    private static volatile Boolean watchDropOnFull;
    private static volatile Boolean traceDropOnFull;
    private static volatile Double traceSampleRate;
    private static volatile Double monitorSampleRate;

    private BootstrapMonitorConfigStore() {}

    public static void setWatchDropOnFull(boolean value) {
        watchDropOnFull = Boolean.valueOf(value);
    }

    public static void setTraceDropOnFull(boolean value) {
        traceDropOnFull = Boolean.valueOf(value);
    }

    public static void setTraceSampleRate(double value) {
        traceSampleRate = Double.valueOf(value);
    }

    public static void setMonitorSampleRate(double value) {
        monitorSampleRate = Double.valueOf(value);
    }

    static Boolean getWatchDropOnFull() {
        return watchDropOnFull;
    }

    static Boolean getTraceDropOnFull() {
        return traceDropOnFull;
    }

    static Double getTraceSampleRate() {
        return traceSampleRate;
    }

    static Double getMonitorSampleRate() {
        return monitorSampleRate;
    }

    public static void clear() {
        watchDropOnFull = null;
        traceDropOnFull = null;
        traceSampleRate = null;
        monitorSampleRate = null;
    }
}

