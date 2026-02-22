package com.javasleuth.foundation.util;

/**
 * JMX MBean interface for MemoryOptimizer monitoring and management
 */
public interface MemoryOptimizerMBean {
    double getHeapUsagePercent();
    long getHeapUsedBytes();
    long getHeapMaxBytes();
    long getNonHeapUsedBytes();
    boolean isAutoGcEnabled();
    void setAutoGcEnabled(boolean enabled);
    long getGcCooldownMs();
    void setGcCooldownMs(long cooldownMs);
    void forceGarbageCollection();
    void clearAllCaches();
    String getMemoryHealth();
}
