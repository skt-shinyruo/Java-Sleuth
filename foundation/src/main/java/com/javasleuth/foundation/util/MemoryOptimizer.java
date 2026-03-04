package com.javasleuth.foundation.util;

import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import java.lang.management.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.management.*;

/**
 * Memory and GC optimization utility for Java-Sleuth
 * Provides intelligent memory management and GC tuning
 */
public class MemoryOptimizer implements MemoryOptimizerMBean {
    private static MemoryOptimizer instance;
    private final ConfigView config;
    private final MemoryMXBean memoryBean;
    private final ScheduledExecutorService memoryMonitor;

    // Memory thresholds
    private static final double MEMORY_WARNING_THRESHOLD = 0.8;
    private static final double MEMORY_CRITICAL_THRESHOLD = 0.9;
    private static final double MEMORY_GC_THRESHOLD = 0.85;

    // GC optimization settings
    private volatile boolean autoGcEnabled = true;
    private volatile long lastGcTime = 0;
    private volatile long gcCooldownMs = 30000; // 30 seconds between forced GCs

    private MemoryOptimizer(ConfigView config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
        this.memoryBean = ManagementFactory.getMemoryMXBean();

        // Create memory monitoring thread
        this.memoryMonitor = Executors.newScheduledThreadPool(
            1,
            SleuthThreadFactory.daemonFixed("sleuth-memory-monitor", Thread.MIN_PRIORITY)
        );

        // Start memory monitoring
        startMemoryMonitoring();

        // Register JMX MBean
        registerMBean();
    }

    public static synchronized MemoryOptimizer getInstance(ConfigView config) {
        if (instance == null) {
            instance = new MemoryOptimizer(config);
        }
        return instance;
    }

    public static synchronized MemoryOptimizer getInstance() {
        return getInstance(ProductionConfig.createDefault());
    }

    /**
     * Start memory monitoring and optimization
     */
    private void startMemoryMonitoring() {
        // Schedule regular memory checks
        memoryMonitor.scheduleAtFixedRate(this::performMemoryCheck, 30, 30, TimeUnit.SECONDS);

        // Schedule cache cleanup
        memoryMonitor.scheduleAtFixedRate(this::performMemoryOptimization, 60, 60, TimeUnit.SECONDS);

        SleuthLogger.debug("Memory optimizer started with monitoring every 30 seconds");
    }

    /**
     * Perform memory health check
     */
    private void performMemoryCheck() {
        try {
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            double usageRatio = (double) heapUsage.getUsed() / heapUsage.getMax();

            if (usageRatio > MEMORY_CRITICAL_THRESHOLD) {
                SleuthLogger.error("🚨 CRITICAL: Memory usage at " + String.format("%.1f%%", usageRatio * 100));
                if (autoGcEnabled) {
                    performEmergencyGC();
                }
            } else if (usageRatio > MEMORY_WARNING_THRESHOLD) {
                SleuthLogger.warn("⚠️ WARNING: Memory usage at " + String.format("%.1f%%", usageRatio * 100));
                if (autoGcEnabled) {
                    suggestGC();
                }
            }

            // Log memory metrics
            if (SleuthConfigSchema.LOGGING_PERFORMANCE_ENABLED.read(config)) {
                logMemoryMetrics(heapUsage, usageRatio);
            }

        } catch (Exception e) {
            SleuthLogger.warn("Error during memory check: " + e.getMessage(), e);
        }
    }

    /**
     * Perform memory optimization tasks
     */
    private void performMemoryOptimization() {
        try {
            // Clear expired caches
            PerformanceOptimizer.clearExpiredCache();

            // Check if GC is needed
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            double usageRatio = (double) heapUsage.getUsed() / heapUsage.getMax();

            if (usageRatio > MEMORY_GC_THRESHOLD && autoGcEnabled) {
                suggestGC();
            }

        } catch (Exception e) {
            SleuthLogger.warn("Error during memory optimization: " + e.getMessage(), e);
        }
    }

    /**
     * Suggest garbage collection if conditions are met
     */
    private void suggestGC() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastGcTime > gcCooldownMs) {
            SleuthLogger.info("💾 Suggesting garbage collection for memory optimization...");
            System.gc();
            lastGcTime = currentTime;
        }
    }

    /**
     * Perform emergency GC for critical memory situations
     */
    private void performEmergencyGC() {
        SleuthLogger.error("🚨 Performing emergency garbage collection!");
        System.gc();
        lastGcTime = System.currentTimeMillis();

        // Wait a moment and check if memory improved
        try {
            Thread.sleep(2000);
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            double usageRatio = (double) heapUsage.getUsed() / heapUsage.getMax();

            if (usageRatio > MEMORY_CRITICAL_THRESHOLD) {
                SleuthLogger.error("🚨 EMERGENCY: Memory still critical after GC - consider increasing heap size!");
            } else {
                SleuthLogger.info("✅ Emergency GC successful - memory usage reduced");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Log detailed memory metrics
     */
    private void logMemoryMetrics(MemoryUsage heapUsage, double usageRatio) {
        StringBuilder metrics = new StringBuilder();
        metrics.append("Memory Status: ");
        metrics.append("Used=").append(formatBytes(heapUsage.getUsed()));
        metrics.append(", Max=").append(formatBytes(heapUsage.getMax()));
        metrics.append(", Usage=").append(String.format("%.1f%%", usageRatio * 100));

        // Add non-heap memory info
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        metrics.append(", NonHeap=").append(formatBytes(nonHeapUsage.getUsed()));

        SleuthLogger.info("📊 " + metrics.toString());
    }

    /**
     * Get comprehensive memory status
     */
    public String getMemoryStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== MEMORY STATUS ===\n");

        // Heap memory
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        double heapUsageRatio = (double) heapUsage.getUsed() / heapUsage.getMax();

        status.append("-- Heap Memory --\n");
        status.append("Used: ").append(formatBytes(heapUsage.getUsed())).append("\n");
        status.append("Committed: ").append(formatBytes(heapUsage.getCommitted())).append("\n");
        status.append("Max: ").append(formatBytes(heapUsage.getMax())).append("\n");
        status.append("Usage: ").append(String.format("%.1f%%", heapUsageRatio * 100));

        if (heapUsageRatio > MEMORY_CRITICAL_THRESHOLD) {
            status.append(" 🚨 CRITICAL");
        } else if (heapUsageRatio > MEMORY_WARNING_THRESHOLD) {
            status.append(" ⚠️ WARNING");
        } else {
            status.append(" ✅ HEALTHY");
        }
        status.append("\n");

        // Non-heap memory
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        status.append("\n-- Non-Heap Memory --\n");
        status.append("Used: ").append(formatBytes(nonHeapUsage.getUsed())).append("\n");
        status.append("Committed: ").append(formatBytes(nonHeapUsage.getCommitted())).append("\n");
        if (nonHeapUsage.getMax() > 0) {
            status.append("Max: ").append(formatBytes(nonHeapUsage.getMax())).append("\n");
        }

        // Memory pools
        status.append("\n-- Memory Pools --\n");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage != null) {
                status.append(pool.getName()).append(": ");
                status.append(formatBytes(usage.getUsed()));
                if (usage.getMax() > 0) {
                    double poolUsage = (double) usage.getUsed() / usage.getMax();
                    status.append(String.format(" (%.1f%%)", poolUsage * 100));
                }
                status.append("\n");
            }
        }

        // GC information
        status.append("\n-- Garbage Collection --\n");
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            status.append(gcBean.getName()).append(": ");
            status.append(gcBean.getCollectionCount()).append(" collections, ");
            status.append(gcBean.getCollectionTime()).append("ms total\n");
        }

        // Optimization settings
        status.append("\n-- Optimization Settings --\n");
        status.append("Auto GC: ").append(autoGcEnabled ? "ENABLED" : "DISABLED").append("\n");
        status.append("GC Cooldown: ").append(gcCooldownMs / 1000).append("s\n");
        status.append("Last GC: ");
        if (lastGcTime > 0) {
            status.append((System.currentTimeMillis() - lastGcTime) / 1000).append("s ago\n");
        } else {
            status.append("Never\n");
        }

        return status.toString();
    }

    /**
     * Get memory optimization recommendations
     */
    public String getOptimizationRecommendations() {
        StringBuilder recommendations = new StringBuilder();
        recommendations.append("=== MEMORY OPTIMIZATION RECOMMENDATIONS ===\n");

        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        double heapUsageRatio = (double) heapUsage.getUsed() / heapUsage.getMax();

        if (heapUsageRatio > MEMORY_CRITICAL_THRESHOLD) {
            recommendations.append("🚨 URGENT ACTIONS REQUIRED:\n");
            recommendations.append("• Increase heap size (-Xmx)\n");
            recommendations.append("• Check for memory leaks\n");
            recommendations.append("• Review large object allocations\n");
            recommendations.append("• Consider enabling aggressive GC\n\n");
        } else if (heapUsageRatio > MEMORY_WARNING_THRESHOLD) {
            recommendations.append("⚠️ RECOMMENDED ACTIONS:\n");
            recommendations.append("• Monitor memory usage trends\n");
            recommendations.append("• Consider tuning cache sizes\n");
            recommendations.append("• Review GC settings\n\n");
        } else {
            recommendations.append("✅ MEMORY USAGE HEALTHY\n\n");
        }

        // GC analysis
        recommendations.append("-- GC Optimization --\n");
        long totalGcTime = 0;
        long totalCollections = 0;

        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            totalGcTime += gcBean.getCollectionTime();
            totalCollections += gcBean.getCollectionCount();
        }

        if (totalCollections > 0) {
            double avgGcTime = (double) totalGcTime / totalCollections;
            if (avgGcTime > 100) { // More than 100ms average
                recommendations.append("• Consider tuning GC parameters for lower latency\n");
                recommendations.append("• Review G1GC settings if not already using\n");
            }
            if (totalCollections > 100) {
                recommendations.append("• High GC frequency detected - review object allocation patterns\n");
            }
        }

        // Cache recommendations
        recommendations.append("\n-- Cache Optimization --\n");
        recommendations.append("• Current cache TTL: ").append(SleuthConfigSchema.PERFORMANCE_CACHE_TTL_MS.read(config)).append("ms\n");
        if (heapUsageRatio > 0.7) {
            recommendations.append("• Consider reducing cache TTL to free memory\n");
        } else {
            recommendations.append("• Consider increasing cache TTL for better performance\n");
        }

        return recommendations.toString();
    }

    /**
     * Format bytes for human readable output
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    // JMX MBean Implementation
    @Override
    public double getHeapUsagePercent() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        return (double) heapUsage.getUsed() / heapUsage.getMax() * 100;
    }

    @Override
    public long getHeapUsedBytes() {
        return memoryBean.getHeapMemoryUsage().getUsed();
    }

    @Override
    public long getHeapMaxBytes() {
        return memoryBean.getHeapMemoryUsage().getMax();
    }

    @Override
    public long getNonHeapUsedBytes() {
        return memoryBean.getNonHeapMemoryUsage().getUsed();
    }

    @Override
    public boolean isAutoGcEnabled() {
        return autoGcEnabled;
    }

    @Override
    public void setAutoGcEnabled(boolean enabled) {
        this.autoGcEnabled = enabled;
        SleuthLogger.info("Auto GC " + (enabled ? "enabled" : "disabled"));
    }

    @Override
    public long getGcCooldownMs() {
        return gcCooldownMs;
    }

    @Override
    public void setGcCooldownMs(long cooldownMs) {
        this.gcCooldownMs = Math.max(5000, cooldownMs); // Minimum 5 seconds
        SleuthLogger.info("GC cooldown set to " + this.gcCooldownMs + "ms");
    }

    @Override
    public void forceGarbageCollection() {
        SleuthLogger.info("Manual garbage collection requested via JMX");
        System.gc();
        lastGcTime = System.currentTimeMillis();
    }

    @Override
    public void clearAllCaches() {
        SleuthLogger.info("Clearing all caches via JMX");
        PerformanceOptimizer.clearCache();
    }

    @Override
    public String getMemoryHealth() {
        double usage = getHeapUsagePercent();
        if (usage > 90) {
            return "CRITICAL";
        } else if (usage > 80) {
            return "WARNING";
        } else {
            return "HEALTHY";
        }
    }

    // JMX Management
    private void registerMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.javasleuth:type=MemoryOptimizer");
            if (!server.isRegistered(name)) {
                server.registerMBean(this, name);
                SleuthLogger.debug("MemoryOptimizer MBean registered");
            }
        } catch (Exception e) {
            SleuthLogger.warn("Failed to register MemoryOptimizer MBean: " + e.getMessage(), e);
        }
    }

    private void unregisterMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.javasleuth:type=MemoryOptimizer");
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
                SleuthLogger.debug("MemoryOptimizer MBean unregistered");
            }
        } catch (Exception e) {
            SleuthLogger.warn("Failed to unregister MemoryOptimizer MBean: " + e.getMessage(), e);
        }
    }

    public static synchronized void shutdownInstance() {
        MemoryOptimizer inst = instance;
        if (inst == null) {
            return;
        }
        try {
            inst.shutdown();
        } catch (Exception ignore) {
            // ignore
        } finally {
            instance = null;
        }
    }

    /**
     * Shutdown memory optimizer
     */
    public void shutdown() {
        SleuthLogger.debug("Shutting down memory optimizer...");

        memoryMonitor.shutdown();
        try {
            if (!memoryMonitor.awaitTermination(5, TimeUnit.SECONDS)) {
                memoryMonitor.shutdownNow();
            }
        } catch (InterruptedException e) {
            memoryMonitor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        unregisterMBean();
        SleuthLogger.debug("Memory optimizer shutdown complete");
    }

}
