package com.javasleuth.foundation.config.model;

/**
 * 监控相关配置（metrics/health/jmx + watch/trace/monitor 队列与采样）。
 */
public final class MonitoringConfig {
    private final boolean metricsEnabled;
    private final boolean healthChecksEnabled;
    private final long cacheCleanupIntervalMs;
    private final boolean jmxEnabled;

    private final int watchQueueCapacity;
    private final boolean watchDropOnFull;

    private final int traceQueueCapacity;
    private final boolean traceDropOnFull;
    private final double traceSampleRate;

    private final double monitorSampleRate;

    public MonitoringConfig(
        boolean metricsEnabled,
        boolean healthChecksEnabled,
        long cacheCleanupIntervalMs,
        boolean jmxEnabled,
        int watchQueueCapacity,
        boolean watchDropOnFull,
        int traceQueueCapacity,
        boolean traceDropOnFull,
        double traceSampleRate,
        double monitorSampleRate
    ) {
        this.metricsEnabled = metricsEnabled;
        this.healthChecksEnabled = healthChecksEnabled;
        this.cacheCleanupIntervalMs = cacheCleanupIntervalMs;
        this.jmxEnabled = jmxEnabled;
        this.watchQueueCapacity = watchQueueCapacity;
        this.watchDropOnFull = watchDropOnFull;
        this.traceQueueCapacity = traceQueueCapacity;
        this.traceDropOnFull = traceDropOnFull;
        this.traceSampleRate = traceSampleRate;
        this.monitorSampleRate = monitorSampleRate;
    }

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public boolean areHealthChecksEnabled() {
        return healthChecksEnabled;
    }

    public long getCacheCleanupIntervalMs() {
        return cacheCleanupIntervalMs;
    }

    public boolean isJmxEnabled() {
        return jmxEnabled;
    }

    public int getWatchQueueCapacity() {
        return watchQueueCapacity;
    }

    public boolean isWatchDropOnFull() {
        return watchDropOnFull;
    }

    public int getTraceQueueCapacity() {
        return traceQueueCapacity;
    }

    public boolean isTraceDropOnFull() {
        return traceDropOnFull;
    }

    public double getTraceSampleRate() {
        return traceSampleRate;
    }

    public double getMonitorSampleRate() {
        return monitorSampleRate;
    }
}

