package com.javasleuth.foundation.config.model;

/**
 * 后台任务（jobs）配置：保留策略与执行上限。
 */
public final class JobsConfig {
    private final int maxJobs;
    private final long ttlMs;
    private final int outputMaxBytes;
    private final int maxRunning;
    private final int queueCapacity;

    public JobsConfig(int maxJobs, long ttlMs, int outputMaxBytes, int maxRunning, int queueCapacity) {
        this.maxJobs = maxJobs;
        this.ttlMs = ttlMs;
        this.outputMaxBytes = outputMaxBytes;
        this.maxRunning = maxRunning;
        this.queueCapacity = queueCapacity;
    }

    public int getMaxJobs() {
        return maxJobs;
    }

    public long getTtlMs() {
        return ttlMs;
    }

    public int getOutputMaxBytes() {
        return outputMaxBytes;
    }

    public int getMaxRunning() {
        return maxRunning;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }
}

