package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.util.PerformanceOptimizer;
import java.lang.management.*;
import java.util.Map;

public class HealthCommand implements Command {
    private final MetricsCollector metricsCollector;
    private final PerformanceOptimizer performanceOptimizer;

    public HealthCommand(MetricsCollector metricsCollector, PerformanceOptimizer performanceOptimizer) {
        this.metricsCollector = metricsCollector;
        if (performanceOptimizer == null) {
            throw new IllegalArgumentException("performanceOptimizer");
        }
        this.performanceOptimizer = performanceOptimizer;
    }

    @Override
    public String execute(String[] args) throws Exception {
        StringBuilder health = new StringBuilder();

        // Overall system health
        boolean isHealthy = metricsCollector.isHealthy();
        health.append("=== SYSTEM HEALTH CHECK ===\n");
        health.append("Overall Status: ").append(isHealthy ? "HEALTHY ✅" : "UNHEALTHY ❌").append("\n");
        health.append("Timestamp: ").append(new java.util.Date()).append("\n\n");

        // Memory health
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapMemory = memoryBean.getHeapMemoryUsage();
        double heapUsedPercent = (double) heapMemory.getUsed() / heapMemory.getMax() * 100;

        health.append("-- Memory Health --\n");
        health.append("Heap Usage: ").append(String.format("%.1f%%", heapUsedPercent));
        if (heapUsedPercent > 85) {
            health.append(" ⚠️ HIGH");
        } else if (heapUsedPercent > 70) {
            health.append(" ⚠️ MODERATE");
        } else {
            health.append(" ✅ GOOD");
        }
        health.append("\n");
        health.append("Heap Used: ").append(formatBytes(heapMemory.getUsed())).append("\n");
        health.append("Heap Max: ").append(formatBytes(heapMemory.getMax())).append("\n");

        // Performance health
        Map<String, Object> perfMetrics = performanceOptimizer.getPerformanceMetrics();

        health.append("\n-- Performance Health --\n");
        long cacheHitRatio = (Long) perfMetrics.get("cacheHitRatio");
        health.append("Cache Hit Ratio: ").append(cacheHitRatio).append("%");
        if (cacheHitRatio > 80) {
            health.append(" ✅ EXCELLENT");
        } else if (cacheHitRatio > 60) {
            health.append(" ✅ GOOD");
        } else if (cacheHitRatio > 40) {
            health.append(" ⚠️ MODERATE");
        } else {
            health.append(" ❌ POOR");
        }
        health.append("\n");

        long slowOperations = (Long) perfMetrics.get("slowOperations");
        long totalOperations = (Long) perfMetrics.get("totalOperations");
        double slowOpPercent = totalOperations > 0 ? (double) slowOperations / totalOperations * 100 : 0;
        health.append("Slow Operations: ").append(String.format("%.1f%%", slowOpPercent));
        if (slowOpPercent > 20) {
            health.append(" ❌ HIGH");
        } else if (slowOpPercent > 10) {
            health.append(" ⚠️ MODERATE");
        } else {
            health.append(" ✅ LOW");
        }
        health.append("\n");

        // Error rate health
        health.append("\n-- Error Rate Health --\n");
        double errorRate = metricsCollector.getErrorRate();
        health.append("Error Rate: ").append(String.format("%.1f%%", errorRate));
        if (errorRate > 10) {
            health.append(" ❌ HIGH");
        } else if (errorRate > 5) {
            health.append(" ⚠️ MODERATE");
        } else {
            health.append(" ✅ LOW");
        }
        health.append("\n");

        // Thread health
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        health.append("\n-- Thread Health --\n");
        health.append("Active Threads: ").append(threadBean.getThreadCount()).append("\n");
        health.append("Daemon Threads: ").append(threadBean.getDaemonThreadCount()).append("\n");

        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        if (deadlockedThreads != null && deadlockedThreads.length > 0) {
            health.append("⚠️ DEADLOCKED THREADS DETECTED: ").append(deadlockedThreads.length).append("\n");
        } else {
            health.append("✅ No deadlocked threads\n");
        }

        // Connection health
        health.append("\n-- Connection Health --\n");
        health.append("Active Connections: ").append(metricsCollector.getActiveConnections()).append("\n");
        health.append("Total Sessions: ").append(metricsCollector.getTotalSessions()).append("\n");
        health.append("Active Sessions: ").append(metricsCollector.getActiveSessions()).append("\n");

        // System uptime
        health.append("\n-- Uptime --\n");
        health.append("System Uptime: ").append(formatDuration(metricsCollector.getUptime())).append("\n");

        // System load (if available)
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean =
                    (com.sun.management.OperatingSystemMXBean) osBean;
                double cpuLoad = sunOsBean.getProcessCpuLoad();
                if (cpuLoad >= 0) {
                    health.append("Process CPU Usage: ").append(String.format("%.1f%%", cpuLoad * 100));
                    if (cpuLoad > 0.8) {
                        health.append(" ❌ HIGH");
                    } else if (cpuLoad > 0.6) {
                        health.append(" ⚠️ MODERATE");
                    } else {
                        health.append(" ✅ NORMAL");
                    }
                    health.append("\n");
                }
            }
        } catch (Exception e) {
            // Ignore - not all JVMs support this
        }

        // Recommendations
        health.append("\n-- Health Recommendations --\n");
        boolean hasRecommendations = false;

        if (heapUsedPercent > 85) {
            health.append("• Consider increasing heap size or optimizing memory usage\n");
            hasRecommendations = true;
        }
        if (errorRate > 5) {
            health.append("• Investigate error causes in audit logs\n");
            hasRecommendations = true;
        }
        if (slowOpPercent > 10) {
            health.append("• Review slow operations and optimize performance\n");
            hasRecommendations = true;
        }
        if (cacheHitRatio < 60) {
            health.append("• Consider adjusting cache TTL settings\n");
            hasRecommendations = true;
        }
        if (deadlockedThreads != null && deadlockedThreads.length > 0) {
            health.append("• URGENT: Investigate and resolve thread deadlocks\n");
            hasRecommendations = true;
        }

        if (!hasRecommendations) {
            health.append("✅ No recommendations - system is operating well\n");
        }

        return health.toString();
    }

    private String formatDuration(long durationMs) {
        if (durationMs < 60000) {
            return String.format("%.1fs", durationMs / 1000.0);
        } else if (durationMs < 3600000) {
            long minutes = durationMs / 60000;
            long seconds = (durationMs % 60000) / 1000;
            return minutes + "m " + seconds + "s";
        } else {
            long hours = durationMs / 3600000;
            long minutes = (durationMs % 3600000) / 60000;
            return hours + "h " + minutes + "m";
        }
    }

    @Override
    public String getDescription() {
        return "Display comprehensive system health status with recommendations";
    }

    public String getUsage() {
        return "health";
    }

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
}
