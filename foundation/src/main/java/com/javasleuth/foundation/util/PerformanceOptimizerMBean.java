package com.javasleuth.foundation.util;

/**
 * JMX MBean interface for PerformanceOptimizer monitoring and management
 */
public interface PerformanceOptimizerMBean {
    /**
     * Get cache hit ratio as a percentage (0-100)
     */
    long getCacheHitRatio();

    /**
     * Get total number of operations executed
     */
    long getTotalOperations();

    /**
     * Get number of slow operations (> 1 second)
     */
    long getSlowOperations();

    /**
     * Get current size of short-term cache
     */
    int getShortTermCacheSize();

    /**
     * Get current size of long-term cache
     */
    int getLongTermCacheSize();

    /**
     * Get number of active threads in thread pool
     */
    int getActiveThreads();

    /**
     * Get current thread pool size
     */
    int getPoolSize();

    /**
     * Clear all caches
     */
    void clearAllCaches();

    /**
     * Force garbage collection
     */
    void forceGarbageCollection();
}
