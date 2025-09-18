package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;
import java.lang.management.*;
import java.text.DecimalFormat;
import java.util.concurrent.TimeUnit;

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
        return PerformanceOptimizer.getCachedResult("dashboard", () -> {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Java-Sleuth Dashboard ===\n");
            sb.append(getJvmInfo()).append("\n");
            sb.append(getMemoryInfo()).append("\n");
            sb.append(getThreadInfo()).append("\n");
            sb.append(getClassLoadingInfo()).append("\n");
            sb.append(getGcInfo()).append("\n");
            return sb.toString();
        });
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

    private String getMemoryInfo() {
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

        return sb.toString();
    }

    private String getThreadInfo() {
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

        return sb.toString();
    }

    private String getClassLoadingInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Class Loading Information:\n");
        sb.append("  Loaded Classes: ").append(classLoadingMXBean.getLoadedClassCount()).append("\n");
        sb.append("  Total Loaded Classes: ").append(classLoadingMXBean.getTotalLoadedClassCount()).append("\n");
        sb.append("  Unloaded Classes: ").append(classLoadingMXBean.getUnloadedClassCount()).append("\n");
        return sb.toString();
    }

    private String getGcInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Garbage Collection Information:\n");

        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append("  ").append(gcBean.getName()).append(":\n");
            sb.append("    Collection Count: ").append(gcBean.getCollectionCount()).append("\n");
            sb.append("    Collection Time: ").append(gcBean.getCollectionTime()).append(" ms\n");
        }

        return sb.toString();
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

    @Override
    public String getDescription() {
        return "Display JVM dashboard with runtime statistics";
    }
}