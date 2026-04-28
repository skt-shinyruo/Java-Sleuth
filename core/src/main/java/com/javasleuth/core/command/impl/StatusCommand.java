package com.javasleuth.core.command.impl;

import com.javasleuth.core.agent.runtime.BootstrapBridge;
import com.javasleuth.core.command.Command;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.bootstrap.monitor.MonitorInterceptor;
import com.javasleuth.bootstrap.monitor.StackInterceptor;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.monitor.TtInterceptor;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import com.javasleuth.foundation.config.ConfigView;

import java.lang.instrument.Instrumentation;
import java.lang.management.*;
import java.util.Map;
import java.util.List;

public class StatusCommand implements Command {
    private final Instrumentation instrumentation;
    private final MetricsCollector metricsCollector;
    private final SleuthClassFileTransformer transformer;
    private final ConfigView config;
    private final PerformanceOptimizer performanceOptimizer;
    private final SleuthSpyDispatcher spyDispatcher;
    private final EnhancementSessionRegistry enhancementSessionRegistry;

    public StatusCommand(
        Instrumentation instrumentation,
        MetricsCollector metricsCollector,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        PerformanceOptimizer performanceOptimizer,
        SleuthSpyDispatcher spyDispatcher
    ) {
        this(instrumentation, metricsCollector, transformer, config, performanceOptimizer, spyDispatcher, null);
    }

    public StatusCommand(
        Instrumentation instrumentation,
        MetricsCollector metricsCollector,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        PerformanceOptimizer performanceOptimizer,
        SleuthSpyDispatcher spyDispatcher,
        EnhancementSessionRegistry enhancementSessionRegistry
    ) {
        this.instrumentation = instrumentation;
        this.metricsCollector = metricsCollector;
        this.transformer = transformer;
        this.config = config;
        if (performanceOptimizer == null) {
            throw new IllegalArgumentException("performanceOptimizer");
        }
        this.performanceOptimizer = performanceOptimizer;
        this.spyDispatcher = spyDispatcher;
        this.enhancementSessionRegistry = enhancementSessionRegistry;
    }

    @Override
    public String execute(String[] args) throws Exception {
        StringBuilder status = new StringBuilder();

        status.append("=== JAVA-SLEUTH AGENT STATUS ===\n");

        // Agent status
        status.append("\n-- Agent Information --\n");
        status.append("Status: ACTIVE ✓\n");
        status.append("Agent Version: 1.0.0\n");
        status.append("Redefine Classes: ").append(instrumentation.isRedefineClassesSupported() ? "Supported" : "Not Supported").append("\n");
        status.append("Retransform Classes: ").append(instrumentation.isRetransformClassesSupported() ? "Supported" : "Not Supported").append("\n");
        status.append("Native Method Prefix: ").append(instrumentation.isNativeMethodPrefixSupported() ? "Supported" : "Not Supported").append("\n");
        status.append("Bootstrap Bridge: ").append(BootstrapBridge.describeStatus()).append("\n");
        if (transformer != null) {
            status.append("Active Enhancers: ").append(transformer.getActiveEnhancersCount()).append("\n");
            status.append("Transformations: ").append(transformer.getTransformationCount()).append("\n");
            status.append("Enhancement Failures: ").append(transformer.getEnhancementFailureCount()).append("\n");
            status.append("Enhancement Cooldown Targets: ").append(transformer.getEnhancementCooldownCount()).append("\n");
            status.append("Enhancement Suppressed (cooldown skips): ").append(transformer.getEnhancementSuppressedCount()).append("\n");
        }

        // System information
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        status.append("\n-- JVM Information --\n");
        status.append("JVM Name: ").append(runtimeBean.getVmName()).append("\n");
        status.append("JVM Version: ").append(runtimeBean.getVmVersion()).append("\n");
        status.append("JVM Vendor: ").append(runtimeBean.getVmVendor()).append("\n");
        status.append("Start Time: ").append(new java.util.Date(runtimeBean.getStartTime())).append("\n");
        status.append("Uptime: ").append(formatDuration(runtimeBean.getUptime())).append("\n");

        // Memory status
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        status.append("\n-- Memory Status --\n");
        status.append("Heap: ").append(formatMemoryUsage(memoryBean.getHeapMemoryUsage())).append("\n");
        status.append("Non-Heap: ").append(formatMemoryUsage(memoryBean.getNonHeapMemoryUsage())).append("\n");

        // Thread information
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        status.append("\n-- Thread Information --\n");
        status.append("Live Threads: ").append(threadBean.getThreadCount()).append("\n");
        status.append("Peak Threads: ").append(threadBean.getPeakThreadCount()).append("\n");
        status.append("Daemon Threads: ").append(threadBean.getDaemonThreadCount()).append("\n");
        status.append("Total Started: ").append(threadBean.getTotalStartedThreadCount()).append("\n");

        // Class loading information
        status.append("\n-- Class Loading --\n");
        status.append("Loaded Classes: ").append(instrumentation.getAllLoadedClasses().length).append("\n");

        // Performance metrics
        Map<String, Object> perfMetrics = performanceOptimizer.getPerformanceMetrics();
        status.append("\n-- Performance Metrics --\n");
        status.append("Cache Hit Ratio: ").append(perfMetrics.get("cacheHitRatio")).append("%\n");
        status.append("Total Operations: ").append(perfMetrics.get("totalOperations")).append("\n");
        status.append("Slow Operations: ").append(perfMetrics.get("slowOperations")).append("\n");
        status.append("Active Threads: ").append(perfMetrics.get("activeThreads")).append("\n");
        status.append("Thread Pool Size: ").append(perfMetrics.get("poolSize")).append("\n");

        // Application metrics summary
        status.append("\n-- Application Metrics --\n");
        status.append("Total Commands: ").append(metricsCollector.getTotalCommands()).append("\n");
        status.append("Total Sessions: ").append(metricsCollector.getTotalSessions()).append("\n");
        status.append("Active Sessions: ").append(metricsCollector.getActiveSessions()).append("\n");
        status.append("Active Connections: ").append(metricsCollector.getActiveConnections()).append("\n");
        status.append("Error Rate: ").append(String.format("%.1f%%", metricsCollector.getErrorRate())).append("\n");
        status.append("Most Executed Command: ").append(metricsCollector.getMostExecutedCommand()).append("\n");
        status.append("Slowest Command: ").append(metricsCollector.getSlowestCommand()).append("\n");
        status.append("Handshakes: ").append(metricsCollector.getHandshakeCount()).append("\n");
        status.append("Binary Upgrades: ").append(metricsCollector.getBinaryUpgradeCount()).append("\n");
        status.append("Plugin Providers Loaded: ").append(metricsCollector.getPluginProviderCount()).append("\n");
        status.append("Plugin Commands Registered: ").append(metricsCollector.getPluginCommandCount()).append("\n");

        // Watch/Trace observability
        status.append("\n-- Watch/Trace Observability --\n");
        SleuthSpyDispatcher d = spyDispatcher;
        boolean dispatcherInstalled = SleuthSpyDispatcher.isInstalled(d);
        status.append("Listener Runtime Installed: ").append(dispatcherInstalled).append("\n");
        if (dispatcherInstalled) {
            status.append("Active Watches: ").append(d.getActiveWatchCount()).append("\n");
            status.append("Active Traces: ").append(d.getActiveTraceCount()).append("\n");
            status.append("Active Listeners: ").append(d.getActiveListenerCount()).append("\n");
            status.append("Active Watch Listeners: ").append(d.getActiveWatchCount()).append("\n");
            status.append("Active Trace Listeners: ").append(d.getActiveTraceCount()).append("\n");
            status.append("Active Monitor Listeners: ").append(d.getActiveMonitorCount()).append("\n");
            status.append("Active Stack Listeners: ").append(d.getActiveStackCount()).append("\n");
            status.append("Active TT Listeners: ").append(d.getActiveTtCount()).append("\n");
            status.append("Active VmTool Listeners: ").append(d.getActiveVmToolCount()).append("\n");
        } else {
            status.append("Active Watches: unavailable\n");
            status.append("Active Traces: unavailable\n");
            status.append("Active Listeners: unavailable\n");
            status.append("Active Watch Listeners: unavailable\n");
            status.append("Active Trace Listeners: unavailable\n");
            status.append("Active Monitor Listeners: unavailable\n");
            status.append("Active Stack Listeners: unavailable\n");
            status.append("Active TT Listeners: unavailable\n");
            status.append("Active VmTool Listeners: unavailable\n");
        }
        status.append("Legacy Active Watches: ").append(WatchInterceptor.getActiveWatchCount()).append("\n");
        status.append("Legacy Active Traces: ").append(TraceInterceptor.getActiveTraceCount()).append("\n");
        status.append("Legacy Watch Published: ").append(WatchInterceptor.getPublishedEventCount()).append("\n");
        status.append("Legacy Watch Dropped: ").append(WatchInterceptor.getDroppedEventCount()).append("\n");
        status.append("Legacy Watch Evicted: ").append(WatchInterceptor.getEvictedEventCount()).append("\n");
        status.append("Legacy Trace Published: ").append(TraceInterceptor.getPublishedEventCount()).append("\n");
        status.append("Legacy Trace Dropped: ").append(TraceInterceptor.getDroppedEventCount()).append("\n");
        status.append("Legacy Trace Evicted: ").append(TraceInterceptor.getEvictedEventCount()).append("\n");
        status.append("Legacy Active Stacks: ").append(StackInterceptor.getActiveStackCount()).append("\n");
        status.append("Stack Published: ").append(StackInterceptor.getPublishedEventCount()).append("\n");
        status.append("Stack Dropped: ").append(StackInterceptor.getDroppedEventCount()).append("\n");
        status.append("Stack Evicted: ").append(StackInterceptor.getEvictedEventCount()).append("\n");
        status.append("Legacy Active Monitors: ").append(MonitorInterceptor.getActiveMonitorCount()).append("\n");
        status.append("Legacy Active TT Sessions: ").append(TtInterceptor.getActiveTtCount()).append("\n");

        status.append("\n-- Enhancement Sessions --\n");
        appendEnhancementSessionStatus(status);

        // Configuration status
        status.append("\n-- Configuration Status --\n");
        status.append("Bind Address: ").append(config.getString("server.bind.address", "127.0.0.1")).append("\n");
        status.append("Server Port: ").append(config.getInt("server.port", 3658)).append("\n");
        status.append("Max Connections: ").append(config.getInt("server.max.connections", 10)).append("\n");
        status.append("Cache TTL: ").append(config.getLong("performance.cache.ttl", 5000)).append("ms\n");
        status.append("Protocol: ").append("binary").append("\n");
        status.append("Handshake Enabled: ").append(true).append("\n");
        status.append("Network Boundary: ").append("loopback-only").append("\n");
        status.append("Input Validation: ").append(config.getBoolean("security.input.validation", true) ? "ENABLED" : "DISABLED").append("\n");
        status.append("Audit Logging: ").append(config.getBoolean("security.audit.logging", true) ? "ENABLED" : "DISABLED").append("\n");
        status.append("Authorization: ").append(config.getBoolean("security.authorization.enabled", false) ? "ENABLED" : "DISABLED").append("\n");
        status.append("Metrics Collection: ").append(config.getBoolean("monitoring.metrics.enabled", true) ? "ENABLED" : "DISABLED").append("\n");
        status.append("JMX Monitoring: ").append(config.getBoolean("monitoring.jmx.enabled", true) ? "ENABLED" : "DISABLED").append("\n");

        // Garbage Collection status
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        if (!gcBeans.isEmpty()) {
            status.append("\n-- Garbage Collection --\n");
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                status.append(gcBean.getName()).append(": ")
                    .append(gcBean.getCollectionCount()).append(" collections, ")
                    .append(gcBean.getCollectionTime()).append("ms total\n");
            }
        }

        // System load information
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            status.append("\n-- System Load --\n");
            status.append("Available Processors: ").append(osBean.getAvailableProcessors()).append("\n");
            status.append("System Load Average: ").append(osBean.getSystemLoadAverage()).append("\n");

            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean =
                    (com.sun.management.OperatingSystemMXBean) osBean;
                status.append("Process CPU Load: ").append(String.format("%.1f%%", sunOsBean.getProcessCpuLoad() * 100)).append("\n");
                status.append("System CPU Load: ").append(String.format("%.1f%%", sunOsBean.getSystemCpuLoad() * 100)).append("\n");
                status.append("Physical Memory: ").append(formatBytes(sunOsBean.getTotalPhysicalMemorySize())).append("\n");
                status.append("Free Physical Memory: ").append(formatBytes(sunOsBean.getFreePhysicalMemorySize())).append("\n");
            }
        } catch (Exception e) {
            // Some JVMs don't support these operations
        }

        // Operational status
        status.append("\n-- Operational Status --\n");
        boolean isHealthy = metricsCollector.isHealthy();
        status.append("Health Check: ").append(isHealthy ? "PASS ✅" : "FAIL ❌").append("\n");
        status.append("Ready for Production: ").append(isHealthy ? "YES ✅" : "NO ❌").append("\n");

        if (!isHealthy) {
            status.append("\n-- Health Issues --\n");
            if (metricsCollector.getHeapUsagePercent() > 85) {
                status.append("⚠️ High memory usage detected\n");
            }
            if (metricsCollector.getErrorRate() > 10) {
                status.append("⚠️ High error rate detected\n");
            }
        }

        return status.toString();
    }

    private void appendEnhancementSessionStatus(StringBuilder status) {
        if (enhancementSessionRegistry == null) {
            status.append("Active Sessions: unavailable\n");
            return;
        }
        Map<EnhancementSessionKind, Integer> counts = enhancementSessionRegistry.countByKind();
        status.append("Active Sessions: ").append(enhancementSessionRegistry.size()).append("\n");
        appendEnhancementCount(status, counts, EnhancementSessionKind.WATCH, "Watch");
        appendEnhancementCount(status, counts, EnhancementSessionKind.TRACE, "Trace");
        appendEnhancementCount(status, counts, EnhancementSessionKind.MONITOR, "Monitor");
        appendEnhancementCount(status, counts, EnhancementSessionKind.STACK, "Stack");
        appendEnhancementCount(status, counts, EnhancementSessionKind.TT, "TT");
        appendEnhancementCount(status, counts, EnhancementSessionKind.VMTOOL, "VmTool");
        appendEnhancementCount(status, counts, EnhancementSessionKind.OTHER, "Other");
    }

    private void appendEnhancementCount(StringBuilder status,
                                        Map<EnhancementSessionKind, Integer> counts,
                                        EnhancementSessionKind kind,
                                        String label) {
        Integer value = counts != null ? counts.get(kind) : null;
        status.append(label).append(": ").append(value != null ? value.intValue() : 0).append("\n");
    }

    @Override
    public String getDescription() {
        return "Display comprehensive agent and system status with performance metrics";
    }

    public String getUsage() {
        return "status";
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

    private String formatMemoryUsage(java.lang.management.MemoryUsage usage) {
        long used = usage.getUsed();
        long max = usage.getMax();

        String usedStr = formatBytes(used);
        String maxStr = max == -1 ? "unlimited" : formatBytes(max);

        if (max > 0) {
            double percent = (double) used / max * 100;
            return String.format("%s / %s (%.1f%%)", usedStr, maxStr, percent);
        } else {
            return String.format("%s / %s", usedStr, maxStr);
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
}
