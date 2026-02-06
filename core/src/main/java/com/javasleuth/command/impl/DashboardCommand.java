package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import java.lang.instrument.Instrumentation;
import java.lang.management.*;
import java.text.DecimalFormat;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;

public class DashboardCommand implements Command {
    private final Instrumentation instrumentation;
    private final ThreadMXBean threadMXBean;
    private final MemoryMXBean memoryMXBean;
    private final RuntimeMXBean runtimeMXBean;
    private final ClassLoadingMXBean classLoadingMXBean;
    private final DecimalFormat df = new DecimalFormat("#,##0.00");

    public DashboardCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        this.threadMXBean = ManagementFactory.getThreadMXBean();
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        this.classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
    }

    @Override
    public String execute(String[] args) throws Exception {
        // Parse optional arguments for detailed view
        boolean detailed = false;
        boolean realtime = false;
        if (args != null) {
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if (a == null) {
                    continue;
                }
                String v = a.trim().toLowerCase();
                if ("detailed".equals(v)) {
                    detailed = true;
                } else if ("realtime".equals(v)) {
                    realtime = true;
                }
            }
        }

        // Backward compatible: "realtime" implies detailed view. Cache policy is enforced by CommandPipeline.
        if (realtime) {
            detailed = true;
        }

        return generateDashboard(detailed);
    }

    private String generateDashboard(boolean detailed) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Java-Sleuth Dashboard ===\n");
        sb.append(getJvmInfo()).append("\n");
        sb.append(getMemoryInfo(detailed)).append("\n");
        sb.append(getThreadInfo(detailed)).append("\n");
        sb.append(getClassLoadingInfo()).append("\n");
        sb.append(getGcInfo(detailed)).append("\n");

        if (detailed) {
            sb.append(getPerformanceMetrics()).append("\n");
            sb.append(getSystemResourceInfo()).append("\n");
        }

        return sb.toString();
    }

    private String getJvmInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("JVM Information:\n");
        sb.append("  Name: ").append(runtimeMXBean.getVmName()).append("\n");
        sb.append("  Version: ").append(runtimeMXBean.getVmVersion()).append("\n");
        sb.append("  Vendor: ").append(runtimeMXBean.getVmVendor()).append("\n");
        sb.append("  Uptime: ").append(formatUptime(runtimeMXBean.getUptime())).append("\n");
        sb.append("  Start Time: ").append(new java.util.Date(runtimeMXBean.getStartTime())).append("\n");
        return sb.toString();
    }

    private String getMemoryInfo(boolean detailed) {
        StringBuilder sb = new StringBuilder();
        MemoryUsage heapMemory = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapMemory = memoryMXBean.getNonHeapMemoryUsage();

        sb.append("Memory Information:\n");
        sb.append("  Heap Memory:\n");
        sb.append("    Used: ").append(formatBytes(heapMemory.getUsed())).append("\n");
        sb.append("    Committed: ").append(formatBytes(heapMemory.getCommitted())).append("\n");
        sb.append("    Max: ").append(formatBytes(heapMemory.getMax())).append("\n");
        sb.append("    Usage: ").append(String.format("%.2f%%",
            (double) heapMemory.getUsed() / heapMemory.getMax() * 100)).append("\n");

        sb.append("  Non-Heap Memory:\n");
        sb.append("    Used: ").append(formatBytes(nonHeapMemory.getUsed())).append("\n");
        sb.append("    Committed: ").append(formatBytes(nonHeapMemory.getCommitted())).append("\n");

        if (detailed) {
            sb.append(getDetailedMemoryInfo());
        }

        return sb.toString();
    }

    private String getMemoryInfo() {
        return getMemoryInfo(false);
    }

    private String getThreadInfo(boolean detailed) {
        StringBuilder sb = new StringBuilder();
        sb.append("Thread Information:\n");
        sb.append("  Thread Count: ").append(threadMXBean.getThreadCount()).append("\n");
        sb.append("  Peak Thread Count: ").append(threadMXBean.getPeakThreadCount()).append("\n");
        sb.append("  Daemon Thread Count: ").append(threadMXBean.getDaemonThreadCount()).append("\n");
        sb.append("  Total Started Thread Count: ").append(threadMXBean.getTotalStartedThreadCount()).append("\n");

        long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
        if (deadlockedThreads != null && deadlockedThreads.length > 0) {
            sb.append("  ⚠️  DEADLOCK DETECTED: ").append(deadlockedThreads.length).append(" threads\n");
        }

        if (detailed) {
            sb.append(getDetailedThreadInfo());
        }

        return sb.toString();
    }

    private String getThreadInfo() {
        return getThreadInfo(false);
    }

    private String getClassLoadingInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Class Loading Information:\n");
        sb.append("  Loaded Classes: ").append(classLoadingMXBean.getLoadedClassCount()).append("\n");
        sb.append("  Total Loaded Classes: ").append(classLoadingMXBean.getTotalLoadedClassCount()).append("\n");
        sb.append("  Unloaded Classes: ").append(classLoadingMXBean.getUnloadedClassCount()).append("\n");
        return sb.toString();
    }

    private String getGcInfo(boolean detailed) {
        StringBuilder sb = new StringBuilder();
        sb.append("Garbage Collection Information:\n");

        long totalCollections = 0;
        long totalTime = 0;

        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long collections = gcBean.getCollectionCount();
            long time = gcBean.getCollectionTime();

            totalCollections += collections;
            totalTime += time;

            sb.append("  ").append(gcBean.getName()).append(":\n");
            sb.append("    Collection Count: ").append(collections).append("\n");
            sb.append("    Collection Time: ").append(time).append(" ms\n");

            if (detailed && collections > 0) {
                sb.append("    Average Time: ").append(time / collections).append(" ms\n");
                sb.append("    Valid: ").append(gcBean.isValid()).append("\n");
            }
        }

        if (detailed) {
            sb.append("  Total Collections: ").append(totalCollections).append("\n");
            sb.append("  Total GC Time: ").append(totalTime).append(" ms\n");
            long uptime = runtimeMXBean.getUptime();
            if (uptime > 0) {
                double gcPercentage = (double) totalTime / uptime * 100;
                sb.append("  GC Time Percentage: ").append(String.format("%.2f%%", gcPercentage)).append("\n");
            }
        }

        return sb.toString();
    }

    private String getGcInfo() {
        return getGcInfo(false);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return df.format(bytes / 1024.0) + " KB";
        if (bytes < 1024 * 1024 * 1024) return df.format(bytes / (1024.0 * 1024.0)) + " MB";
        return df.format(bytes / (1024.0 * 1024.0 * 1024.0)) + " GB";
    }

    private String formatUptime(long uptimeMs) {
        long days = TimeUnit.MILLISECONDS.toDays(uptimeMs);
        long hours = TimeUnit.MILLISECONDS.toHours(uptimeMs) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMs) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMs) % 60;

        if (days > 0) {
            return String.format("%dd %dh %dm %ds", days, hours, minutes, seconds);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    private String getDetailedMemoryInfo() {
        StringBuilder sb = new StringBuilder();

        try {
            // Memory pool information
            sb.append("  Memory Pools:\n");
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                MemoryUsage usage = pool.getUsage();
                if (usage != null) {
                    sb.append("    ").append(pool.getName()).append(":\n");
                    sb.append("      Used: ").append(formatBytes(usage.getUsed())).append("\n");
                    sb.append("      Max: ").append(usage.getMax() > 0 ? formatBytes(usage.getMax()) : "undefined").append("\n");
                    sb.append("      Type: ").append(pool.getType()).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("    Error getting memory pool info: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    private String getDetailedThreadInfo() {
        StringBuilder sb = new StringBuilder();

        try {
            // Thread state breakdown
            long[] allThreadIds = threadMXBean.getAllThreadIds();
            ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(allThreadIds);

            Map<Thread.State, Integer> stateCount = new HashMap<>();
            int blockedCount = 0;

            for (ThreadInfo info : threadInfos) {
                if (info != null) {
                    Thread.State state = info.getThreadState();
                    stateCount.put(state, stateCount.getOrDefault(state, 0) + 1);

                    if (state == Thread.State.BLOCKED || info.getLockName() != null) {
                        blockedCount++;
                    }
                }
            }

            sb.append("  Thread States:\n");
            for (Map.Entry<Thread.State, Integer> entry : stateCount.entrySet()) {
                sb.append("    ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }

            if (blockedCount > 0) {
                sb.append("  Blocked/Waiting Threads: ").append(blockedCount).append("\n");
            }

            // CPU time if available
            if (threadMXBean.isThreadCpuTimeSupported() && threadMXBean.isThreadCpuTimeEnabled()) {
                sb.append("  CPU Time Tracking: Enabled\n");
            }

        } catch (Exception e) {
            sb.append("    Error getting detailed thread info: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    private String getPerformanceMetrics() {
        StringBuilder sb = new StringBuilder();
        sb.append("Performance Metrics:\n");

        try {
            // JIT compilation info
            CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
            if (compilationBean != null) {
                sb.append("  JIT Compiler: ").append(compilationBean.getName()).append("\n");
                if (compilationBean.isCompilationTimeMonitoringSupported()) {
                    sb.append("  Total Compilation Time: ").append(compilationBean.getTotalCompilationTime()).append(" ms\n");
                }
            }

            // Memory allocation rate estimation
            long currentHeapUsed = memoryMXBean.getHeapMemoryUsage().getUsed();
            long uptime = runtimeMXBean.getUptime();
            if (uptime > 0) {
                double allocationRate = (double) currentHeapUsed / uptime * 1000; // bytes per second
                sb.append("  Est. Allocation Rate: ").append(formatBytes((long) allocationRate)).append("/sec\n");
            }

        } catch (Exception e) {
            sb.append("  Error getting performance metrics: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    private String getSystemResourceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("System Resources:\n");

        try {
            // Operating system info
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            sb.append("  OS: ").append(osBean.getName()).append(" ").append(osBean.getVersion()).append("\n");
            sb.append("  Architecture: ").append(osBean.getArch()).append("\n");
            sb.append("  Available Processors: ").append(osBean.getAvailableProcessors()).append("\n");

            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean =
                    (com.sun.management.OperatingSystemMXBean) osBean;

                sb.append("  Process CPU Load: ").append(String.format("%.2f%%",
                    sunOsBean.getProcessCpuLoad() * 100)).append("\n");
                sb.append("  System CPU Load: ").append(String.format("%.2f%%",
                    sunOsBean.getSystemCpuLoad() * 100)).append("\n");
                sb.append("  Total Physical Memory: ").append(formatBytes(sunOsBean.getTotalPhysicalMemorySize())).append("\n");
                sb.append("  Free Physical Memory: ").append(formatBytes(sunOsBean.getFreePhysicalMemorySize())).append("\n");
            }

        } catch (Exception e) {
            sb.append("  Error getting system resource info: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    @Override
    public String getDescription() {
        return "Display JVM dashboard with runtime statistics (use 'detailed' or 'realtime' options)";
    }
}
