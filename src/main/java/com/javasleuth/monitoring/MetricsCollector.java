package com.javasleuth.monitoring;

import java.lang.management.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import javax.management.*;

public class MetricsCollector implements MetricsCollectorMBean {
    private final AtomicLong totalCommands = new AtomicLong(0);
    private final AtomicLong totalSessions = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong serverStartTime = new AtomicLong(0);
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    // Protocol / plugin observability
    private final AtomicLong handshakeCount = new AtomicLong(0);
    private final AtomicLong binaryUpgradeCount = new AtomicLong(0);
    private final AtomicLong pluginProviderCount = new AtomicLong(0);
    private final AtomicLong pluginCommandCount = new AtomicLong(0);

    private final ConcurrentHashMap<String, AtomicLong> commandCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> commandDurations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> errorCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> activeSessions = new ConcurrentHashMap<>();

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

    // Performance tracking
    private final ConcurrentHashMap<String, CommandMetrics> commandMetrics = new ConcurrentHashMap<>();
    private final ScheduledExecutorService metricsExecutor;

    // Health thresholds
    private static final double MEMORY_WARNING_THRESHOLD = 85.0;
    private static final double ERROR_RATE_THRESHOLD = 10.0;
    private static final long SLOW_COMMAND_THRESHOLD = 5000; // 5 seconds

    private static class CommandMetrics {
        final AtomicLong count = new AtomicLong(0);
        final AtomicLong totalDuration = new AtomicLong(0);
        final AtomicLong maxDuration = new AtomicLong(0);
        final AtomicLong errors = new AtomicLong(0);
        volatile long lastExecuted = System.currentTimeMillis();

        void recordExecution(long durationMs, boolean success) {
            count.incrementAndGet();
            totalDuration.addAndGet(durationMs);
            maxDuration.updateAndGet(current -> Math.max(current, durationMs));
            if (!success) {
                errors.incrementAndGet();
            }
            lastExecuted = System.currentTimeMillis();
        }

        double getAverageDuration() {
            long c = count.get();
            return c > 0 ? (double) totalDuration.get() / c : 0.0;
        }

        double getErrorRate() {
            long c = count.get();
            return c > 0 ? (double) errors.get() / c * 100 : 0.0;
        }
    }

    public MetricsCollector() {
        // Initialize metrics collection scheduler
        this.metricsExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "sleuth-metrics");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        // Schedule periodic metrics collection
        metricsExecutor.scheduleAtFixedRate(this::collectPeriodicMetrics, 30, 30, TimeUnit.SECONDS);

        // Register JMX MBean
        registerMBean();
    }

    private void collectPeriodicMetrics() {
        try {
            // Collect and log key metrics periodically
            if (isPerformanceLogEnabled()) {
                logPerformanceMetrics();
            }
        } catch (Exception e) {
            System.err.println("Error collecting periodic metrics: " + e.getMessage());
        }
    }

    private boolean isPerformanceLogEnabled() {
        // This could be controlled by configuration
        return true; // For now, always enabled
    }

    private void logPerformanceMetrics() {
        MemoryUsage heapMemory = memoryBean.getHeapMemoryUsage();
        double heapUsedPercent = (double) heapMemory.getUsed() / heapMemory.getMax() * 100;

        if (heapUsedPercent > MEMORY_WARNING_THRESHOLD) {
            System.out.println("WARNING: High memory usage detected: " + String.format("%.1f%%", heapUsedPercent));
        }

        double errorRate = calculateErrorRate();
        if (errorRate > ERROR_RATE_THRESHOLD) {
            System.out.println("WARNING: High error rate detected: " + String.format("%.1f%%", errorRate));
        }
    }

    public void recordServerStartup() {
        serverStartTime.set(System.currentTimeMillis());
    }

    public void recordServerShutdown() {
        // Record shutdown metric
    }

    public void recordClientConnection() {
        activeConnections.incrementAndGet();
    }

    public void recordClientDisconnection() {
        activeConnections.decrementAndGet();
    }

    public void recordSessionStart(String sessionId) {
        totalSessions.incrementAndGet();
        activeSessions.put(sessionId, System.currentTimeMillis());
    }

    public void recordSessionEnd(String sessionId, long duration) {
        activeSessions.remove(sessionId);
    }

    public void recordCommandStart(String commandName) {
        totalCommands.incrementAndGet();
        commandCounts.computeIfAbsent(commandName, k -> new AtomicLong(0)).incrementAndGet();
        // Initialize command metrics if not exists
        commandMetrics.computeIfAbsent(commandName, k -> new CommandMetrics());
    }

    public void recordCommandComplete(String commandName, long durationMs) {
        commandDurations.computeIfAbsent(commandName, k -> new AtomicLong(0)).addAndGet(durationMs);

        // Record in enhanced metrics
        CommandMetrics metrics = commandMetrics.get(commandName);
        if (metrics != null) {
            metrics.recordExecution(durationMs, true);

            if (durationMs > SLOW_COMMAND_THRESHOLD) {
                System.out.println("SLOW COMMAND: " + commandName + " took " + durationMs + "ms");
            }
        }
    }

    public void recordCommandError(String commandName, long durationMs) {
        CommandMetrics metrics = commandMetrics.get(commandName);
        if (metrics != null) {
            metrics.recordExecution(durationMs, false);
        }
    }

    public void recordError(String errorType) {
        totalErrors.incrementAndGet();
        errorCounts.computeIfAbsent(errorType, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordHandshake() {
        handshakeCount.incrementAndGet();
    }

    public void recordBinaryUpgrade() {
        binaryUpgradeCount.incrementAndGet();
    }

    public void recordPluginProviderLoaded() {
        pluginProviderCount.incrementAndGet();
    }

    public void recordPluginCommandRegistered() {
        pluginCommandCount.incrementAndGet();
    }

    public String getHealthStatus() {
        MemoryUsage heapMemory = memoryBean.getHeapMemoryUsage();
        double heapUsedPercent = (double) heapMemory.getUsed() / heapMemory.getMax() * 100;

        StringBuilder health = new StringBuilder();
        health.append("=== HEALTH STATUS ===\n");
        health.append("Status: ").append(heapUsedPercent < 85 ? "HEALTHY" : "WARNING").append("\n");
        health.append("Uptime: ").append(formatDuration(System.currentTimeMillis() - serverStartTime.get())).append("\n");
        health.append("Active Connections: ").append(activeConnections.get()).append("\n");
        health.append("Total Sessions: ").append(totalSessions.get()).append("\n");
        health.append("Heap Usage: ").append(String.format("%.1f%%", heapUsedPercent)).append("\n");
        health.append("Total Threads: ").append(threadBean.getThreadCount()).append("\n");
        health.append("Error Rate: ").append(calculateErrorRate()).append("%\n");

        return health.toString();
    }

    public String getDetailedMetrics() {
        StringBuilder metrics = new StringBuilder();

        metrics.append("=== SYSTEM METRICS ===\n");

        // Server metrics
        metrics.append("\n-- Server Statistics --\n");
        metrics.append("Uptime: ").append(formatDuration(System.currentTimeMillis() - serverStartTime.get())).append("\n");
        metrics.append("Total Commands Executed: ").append(totalCommands.get()).append("\n");
        metrics.append("Total Sessions: ").append(totalSessions.get()).append("\n");
        metrics.append("Active Sessions: ").append(activeSessions.size()).append("\n");
        metrics.append("Total Errors: ").append(totalErrors.get()).append("\n");
        metrics.append("Handshakes: ").append(handshakeCount.get()).append("\n");
        metrics.append("Binary Upgrades: ").append(binaryUpgradeCount.get()).append("\n");
        metrics.append("Plugin Providers Loaded: ").append(pluginProviderCount.get()).append("\n");
        metrics.append("Plugin Commands Registered: ").append(pluginCommandCount.get()).append("\n");

        // Memory metrics
        metrics.append("\n-- Memory Usage --\n");
        MemoryUsage heapMemory = memoryBean.getHeapMemoryUsage();
        metrics.append("Heap Used: ").append(formatBytes(heapMemory.getUsed())).append("\n");
        metrics.append("Heap Max: ").append(formatBytes(heapMemory.getMax())).append("\n");
        metrics.append("Heap Usage: ").append(String.format("%.1f%%",
            (double) heapMemory.getUsed() / heapMemory.getMax() * 100)).append("\n");

        MemoryUsage nonHeapMemory = memoryBean.getNonHeapMemoryUsage();
        metrics.append("Non-Heap Used: ").append(formatBytes(nonHeapMemory.getUsed())).append("\n");

        // Thread metrics
        metrics.append("\n-- Thread Information --\n");
        metrics.append("Current Threads: ").append(threadBean.getThreadCount()).append("\n");
        metrics.append("Peak Threads: ").append(threadBean.getPeakThreadCount()).append("\n");
        metrics.append("Daemon Threads: ").append(threadBean.getDaemonThreadCount()).append("\n");

        // System metrics
        metrics.append("\n-- System Information --\n");
        metrics.append("OS: ").append(System.getProperty("os.name")).append(" ")
                .append(System.getProperty("os.version")).append("\n");
        metrics.append("Java Version: ").append(System.getProperty("java.version")).append("\n");
        metrics.append("Available Processors: ").append(osBean.getAvailableProcessors()).append("\n");

        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean =
                (com.sun.management.OperatingSystemMXBean) osBean;
            metrics.append("Process CPU Load: ").append(String.format("%.1f%%",
                sunOsBean.getProcessCpuLoad() * 100)).append("\n");
            metrics.append("System CPU Load: ").append(String.format("%.1f%%",
                sunOsBean.getSystemCpuLoad() * 100)).append("\n");
        }

        // Command statistics
        if (!commandCounts.isEmpty()) {
            metrics.append("\n-- Command Statistics --\n");
            for (Map.Entry<String, AtomicLong> entry : commandCounts.entrySet()) {
                String cmd = entry.getKey();
                long count = entry.getValue().get();
                AtomicLong durationSum = commandDurations.get(cmd);
                long avgDuration = durationSum != null && count > 0 ?
                    durationSum.get() / count : 0;

                metrics.append(String.format("%-15s: %d executions, avg %dms\n",
                    cmd, count, avgDuration));
            }
        }

        // Error statistics
        if (!errorCounts.isEmpty()) {
            metrics.append("\n-- Error Statistics --\n");
            for (Map.Entry<String, AtomicLong> entry : errorCounts.entrySet()) {
                metrics.append(String.format("%-20s: %d occurrences\n",
                    entry.getKey(), entry.getValue().get()));
            }
        }

        // Enhanced command statistics with error rates
        if (!commandMetrics.isEmpty()) {
            metrics.append("\n-- Enhanced Command Statistics --\n");
            commandMetrics.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().count.get(), e1.getValue().count.get()))
                .limit(10)
                .forEach(entry -> {
                    String cmd = entry.getKey();
                    CommandMetrics cmdMetrics = entry.getValue();
                    metrics.append(String.format("%-15s: %d exec, avg %.1fms, max %dms, err %.1f%%\n",
                        cmd,
                        cmdMetrics.count.get(),
                        cmdMetrics.getAverageDuration(),
                        cmdMetrics.maxDuration.get(),
                        cmdMetrics.getErrorRate()));
                });
        }

        // Garbage Collection statistics
        if (!gcBeans.isEmpty()) {
            metrics.append("\n-- Garbage Collection --\n");
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                metrics.append(String.format("%-20s: %d collections, %dms total\n",
                    gcBean.getName(),
                    gcBean.getCollectionCount(),
                    gcBean.getCollectionTime()));
            }
        }

        return metrics.toString();
    }

    private double calculateErrorRate() {
        long totalOps = totalCommands.get();
        if (totalOps == 0) return 0.0;
        return ((double) totalErrors.get() / totalOps) * 100;
    }

    private String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        } else if (durationMs < 60000) {
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


    public Map<String, Object> getMetricsMap() {
        Map<String, Object> metricsMap = new HashMap<>();

        metricsMap.put("uptime", getUptime());
        metricsMap.put("totalCommands", getTotalCommands());
        metricsMap.put("totalSessions", getTotalSessions());
        metricsMap.put("activeSessions", getActiveSessions());
        metricsMap.put("totalErrors", getTotalErrors());
        metricsMap.put("errorRate", getErrorRate());
        metricsMap.put("handshakes", handshakeCount.get());
        metricsMap.put("binaryUpgrades", binaryUpgradeCount.get());
        metricsMap.put("pluginProvidersLoaded", pluginProviderCount.get());
        metricsMap.put("pluginCommandsRegistered", pluginCommandCount.get());
        metricsMap.put("activeConnections", getActiveConnections());
        metricsMap.put("threadCount", getThreadCount());
        metricsMap.put("heapUsagePercent", getHeapUsagePercent());
        metricsMap.put("isHealthy", isHealthy());
        metricsMap.put("mostExecutedCommand", getMostExecutedCommand());
        metricsMap.put("slowestCommand", getSlowestCommand());

        // GC metrics
        Map<String, Object> gcMetrics = new HashMap<>();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            Map<String, Object> gcData = new HashMap<>();
            gcData.put("collections", gcBean.getCollectionCount());
            gcData.put("time", gcBean.getCollectionTime());
            gcMetrics.put(gcBean.getName(), gcData);
        }
        metricsMap.put("garbageCollection", gcMetrics);

        return metricsMap;
    }

    // JMX MBean Implementation
    @Override
    public long getUptime() {
        return System.currentTimeMillis() - serverStartTime.get();
    }

    @Override
    public long getTotalCommands() {
        return totalCommands.get();
    }

    @Override
    public long getTotalSessions() {
        return totalSessions.get();
    }

    @Override
    public int getActiveSessions() {
        return activeSessions.size();
    }

    @Override
    public long getTotalErrors() {
        return totalErrors.get();
    }

    public long getHandshakeCount() {
        return handshakeCount.get();
    }

    public long getBinaryUpgradeCount() {
        return binaryUpgradeCount.get();
    }

    public long getPluginProviderCount() {
        return pluginProviderCount.get();
    }

    public long getPluginCommandCount() {
        return pluginCommandCount.get();
    }

    @Override
    public double getErrorRate() {
        return calculateErrorRate();
    }

    @Override
    public int getActiveConnections() {
        return activeConnections.get();
    }

    @Override
    public double getHeapUsagePercent() {
        MemoryUsage heapMemory = memoryBean.getHeapMemoryUsage();
        return (double) heapMemory.getUsed() / heapMemory.getMax() * 100;
    }

    @Override
    public int getThreadCount() {
        return threadBean.getThreadCount();
    }

    @Override
    public boolean isHealthy() {
        return getHeapUsagePercent() < MEMORY_WARNING_THRESHOLD && getErrorRate() < ERROR_RATE_THRESHOLD;
    }

    @Override
    public void resetMetrics() {
        totalCommands.set(0);
        totalSessions.set(0);
        totalErrors.set(0);
        commandCounts.clear();
        commandDurations.clear();
        errorCounts.clear();
        commandMetrics.clear();
        System.out.println("All metrics have been reset");
    }

    @Override
    public String getMostExecutedCommand() {
        return commandMetrics.entrySet().stream()
            .max((e1, e2) -> Long.compare(e1.getValue().count.get(), e2.getValue().count.get()))
            .map(Map.Entry::getKey)
            .orElse("none");
    }

    @Override
    public String getSlowestCommand() {
        return commandMetrics.entrySet().stream()
            .max((e1, e2) -> Double.compare(e1.getValue().getAverageDuration(), e2.getValue().getAverageDuration()))
            .map(Map.Entry::getKey)
            .orElse("none");
    }

    // JMX Management
    private void registerMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.javasleuth:type=MetricsCollector");
            if (!server.isRegistered(name)) {
                server.registerMBean(this, name);
                System.out.println("MetricsCollector MBean registered");
            }
        } catch (Exception e) {
            System.err.println("Failed to register MetricsCollector MBean: " + e.getMessage());
        }
    }

    private void unregisterMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.javasleuth:type=MetricsCollector");
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
                System.out.println("MetricsCollector MBean unregistered");
            }
        } catch (Exception e) {
            System.err.println("Failed to unregister MetricsCollector MBean: " + e.getMessage());
        }
    }

    public void shutdown() {
        System.out.println("Shutting down metrics collector...");

        metricsExecutor.shutdown();
        try {
            if (!metricsExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                metricsExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            metricsExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        unregisterMBean();
        System.out.println("Metrics collector shutdown complete");
    }
}
