package com.javasleuth.foundation.config.model;

/**
 * 性能与线程池相关配置（缓存 TTL、执行器大小、命令超时等）。
 */
public final class PerformanceConfig {
    private final long cacheTtlMs;
    private final int threadPoolCoreSize;
    private final int threadPoolMaxSize;
    private final int commandExecutorCoreSize;
    private final int commandExecutorMaxSize;
    private final int commandExecutorQueueCapacity;
    private final int commandStreamExecutorCoreSize;
    private final int commandStreamExecutorMaxSize;
    private final int commandStreamExecutorQueueCapacity;
    private final long commandTimeoutMs;
    private final long commandTimeoutMaxMs;
    private final boolean maintenanceForceGc;

    public PerformanceConfig(
        long cacheTtlMs,
        int threadPoolCoreSize,
        int threadPoolMaxSize,
        int commandExecutorCoreSize,
        int commandExecutorMaxSize,
        int commandExecutorQueueCapacity,
        int commandStreamExecutorCoreSize,
        int commandStreamExecutorMaxSize,
        int commandStreamExecutorQueueCapacity,
        long commandTimeoutMs,
        long commandTimeoutMaxMs,
        boolean maintenanceForceGc
    ) {
        this.cacheTtlMs = cacheTtlMs;
        this.threadPoolCoreSize = threadPoolCoreSize;
        this.threadPoolMaxSize = threadPoolMaxSize;
        this.commandExecutorCoreSize = commandExecutorCoreSize;
        this.commandExecutorMaxSize = commandExecutorMaxSize;
        this.commandExecutorQueueCapacity = commandExecutorQueueCapacity;
        this.commandStreamExecutorCoreSize = commandStreamExecutorCoreSize;
        this.commandStreamExecutorMaxSize = commandStreamExecutorMaxSize;
        this.commandStreamExecutorQueueCapacity = commandStreamExecutorQueueCapacity;
        this.commandTimeoutMs = commandTimeoutMs;
        this.commandTimeoutMaxMs = commandTimeoutMaxMs;
        this.maintenanceForceGc = maintenanceForceGc;
    }

    public long getCacheTtlMs() {
        return cacheTtlMs;
    }

    public int getThreadPoolCoreSize() {
        return threadPoolCoreSize;
    }

    public int getThreadPoolMaxSize() {
        return threadPoolMaxSize;
    }

    public int getCommandExecutorCoreSize() {
        return commandExecutorCoreSize;
    }

    public int getCommandExecutorMaxSize() {
        return commandExecutorMaxSize;
    }

    public int getCommandExecutorQueueCapacity() {
        return commandExecutorQueueCapacity;
    }

    public int getCommandStreamExecutorCoreSize() {
        return commandStreamExecutorCoreSize;
    }

    public int getCommandStreamExecutorMaxSize() {
        return commandStreamExecutorMaxSize;
    }

    public int getCommandStreamExecutorQueueCapacity() {
        return commandStreamExecutorQueueCapacity;
    }

    public long getCommandTimeoutMs() {
        return commandTimeoutMs;
    }

    public long getCommandTimeoutMaxMs() {
        return commandTimeoutMaxMs;
    }

    public boolean isMaintenanceForceGc() {
        return maintenanceForceGc;
    }
}
