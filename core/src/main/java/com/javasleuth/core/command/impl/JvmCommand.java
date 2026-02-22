package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import java.lang.instrument.Instrumentation;
import java.lang.management.*;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class JvmCommand implements Command {
    private final Instrumentation instrumentation;
    private final RuntimeMXBean runtimeMXBean;
    private final OperatingSystemMXBean osMXBean;
    private final ClassLoadingMXBean classLoadingMXBean;
    private final CompilationMXBean compilationMXBean;
    private final MemoryMXBean memoryMXBean;
    private final DecimalFormat df = new DecimalFormat("#,##0.00");

    public JvmCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        this.runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        this.osMXBean = ManagementFactory.getOperatingSystemMXBean();
        this.classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
        this.compilationMXBean = ManagementFactory.getCompilationMXBean();
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length > 1 && "--help".equals(args[1])) {
            return getHelpText();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== JVM Information ===\n");
        sb.append(getVmDetails()).append("\n");
        sb.append(getOperatingSystemInfo()).append("\n");
        sb.append(getRuntimeInfo()).append("\n");
        sb.append(getClassLoadingInfo()).append("\n");
        sb.append(getCompilationInfo()).append("\n");
        sb.append(getMemorySummary()).append("\n");
        sb.append(getJvmArguments()).append("\n");
        return sb.toString();
    }

    private String getVmDetails() {
        StringBuilder sb = new StringBuilder();
        sb.append("Virtual Machine:\n");
        sb.append("  Name: ").append(runtimeMXBean.getVmName()).append("\n");
        sb.append("  Version: ").append(runtimeMXBean.getVmVersion()).append("\n");
        sb.append("  Vendor: ").append(runtimeMXBean.getVmVendor()).append("\n");
        sb.append("  Specification Name: ").append(runtimeMXBean.getSpecName()).append("\n");
        sb.append("  Specification Version: ").append(runtimeMXBean.getSpecVersion()).append("\n");
        sb.append("  Specification Vendor: ").append(runtimeMXBean.getSpecVendor()).append("\n");
        sb.append("  Management Specification Version: ").append(runtimeMXBean.getManagementSpecVersion()).append("\n");
        return sb.toString();
    }

    private String getOperatingSystemInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Operating System:\n");
        sb.append("  Name: ").append(osMXBean.getName()).append("\n");
        sb.append("  Version: ").append(osMXBean.getVersion()).append("\n");
        sb.append("  Architecture: ").append(osMXBean.getArch()).append("\n");
        sb.append("  Available Processors: ").append(osMXBean.getAvailableProcessors()).append("\n");

        // Additional OS info if available (requires com.sun.management.OperatingSystemMXBean)
        if (osMXBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsMXBean =
                (com.sun.management.OperatingSystemMXBean) osMXBean;
            sb.append("  Total Physical Memory: ").append(formatBytes(sunOsMXBean.getTotalPhysicalMemorySize())).append("\n");
            sb.append("  Free Physical Memory: ").append(formatBytes(sunOsMXBean.getFreePhysicalMemorySize())).append("\n");
            sb.append("  Total Swap Space: ").append(formatBytes(sunOsMXBean.getTotalSwapSpaceSize())).append("\n");
            sb.append("  Free Swap Space: ").append(formatBytes(sunOsMXBean.getFreeSwapSpaceSize())).append("\n");
            sb.append("  Process CPU Load: ").append(String.format("%.2f%%", sunOsMXBean.getProcessCpuLoad() * 100)).append("\n");
            sb.append("  System CPU Load: ").append(String.format("%.2f%%", sunOsMXBean.getSystemCpuLoad() * 100)).append("\n");
            sb.append("  Process CPU Time: ").append(formatDuration(sunOsMXBean.getProcessCpuTime() / 1_000_000)).append("\n");
        }

        return sb.toString();
    }

    private String getRuntimeInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Runtime Information:\n");
        sb.append("  Process ID: ").append(runtimeMXBean.getName().split("@")[0]).append("\n");
        sb.append("  Start Time: ").append(new Date(runtimeMXBean.getStartTime())).append("\n");
        sb.append("  Uptime: ").append(formatUptime(runtimeMXBean.getUptime())).append("\n");
        sb.append("  Library Path: ").append(runtimeMXBean.getLibraryPath()).append("\n");
        sb.append("  Class Path: ").append(runtimeMXBean.getClassPath()).append("\n");
        sb.append("  Boot Class Path Supported: ").append(runtimeMXBean.isBootClassPathSupported()).append("\n");
        if (runtimeMXBean.isBootClassPathSupported()) {
            sb.append("  Boot Class Path: ").append(runtimeMXBean.getBootClassPath()).append("\n");
        }
        return sb.toString();
    }

    private String getClassLoadingInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Class Loading:\n");
        sb.append("  Currently Loaded Classes: ").append(classLoadingMXBean.getLoadedClassCount()).append("\n");
        sb.append("  Total Loaded Classes: ").append(classLoadingMXBean.getTotalLoadedClassCount()).append("\n");
        sb.append("  Unloaded Classes: ").append(classLoadingMXBean.getUnloadedClassCount()).append("\n");
        sb.append("  Verbose Class Loading: ").append(classLoadingMXBean.isVerbose()).append("\n");
        return sb.toString();
    }

    private String getCompilationInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("JIT Compilation:\n");
        if (compilationMXBean != null) {
            sb.append("  Compiler Name: ").append(compilationMXBean.getName()).append("\n");
            sb.append("  Compilation Time Monitoring Supported: ").append(compilationMXBean.isCompilationTimeMonitoringSupported()).append("\n");
            if (compilationMXBean.isCompilationTimeMonitoringSupported()) {
                sb.append("  Total Compilation Time: ").append(formatDuration(compilationMXBean.getTotalCompilationTime())).append("\n");
            }
        } else {
            sb.append("  JIT Compiler: Not available\n");
        }
        return sb.toString();
    }

    private String getMemorySummary() {
        StringBuilder sb = new StringBuilder();
        MemoryUsage heapMemory = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapMemory = memoryMXBean.getNonHeapMemoryUsage();

        sb.append("Memory Summary:\n");
        sb.append("  Heap Memory: ").append(formatMemoryUsage(heapMemory)).append("\n");
        sb.append("  Non-Heap Memory: ").append(formatMemoryUsage(nonHeapMemory)).append("\n");
        sb.append("  Pending Finalization: ").append(memoryMXBean.getObjectPendingFinalizationCount()).append(" objects\n");
        return sb.toString();
    }

    private String getJvmArguments() {
        StringBuilder sb = new StringBuilder();
        sb.append("JVM Arguments:\n");

        List<String> inputArguments = runtimeMXBean.getInputArguments();
        if (inputArguments.isEmpty()) {
            sb.append("  No JVM arguments specified\n");
        } else {
            for (int i = 0; i < inputArguments.size(); i++) {
                sb.append("  [").append(i + 1).append("] ").append(inputArguments.get(i)).append("\n");
            }
        }

        return sb.toString();
    }

    private String formatMemoryUsage(MemoryUsage usage) {
        if (usage.getMax() == -1) {
            return String.format("used: %s, committed: %s, max: unlimited",
                formatBytes(usage.getUsed()), formatBytes(usage.getCommitted()));
        } else {
            double percentage = (double) usage.getUsed() / usage.getMax() * 100;
            return String.format("used: %s, committed: %s, max: %s (%.2f%% used)",
                formatBytes(usage.getUsed()), formatBytes(usage.getCommitted()),
                formatBytes(usage.getMax()), percentage);
        }
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

    private String formatDuration(long milliseconds) {
        if (milliseconds < 1000) {
            return milliseconds + " ms";
        } else if (milliseconds < 60000) {
            return df.format(milliseconds / 1000.0) + " s";
        } else {
            long minutes = milliseconds / 60000;
            long seconds = (milliseconds % 60000) / 1000;
            return minutes + "m " + seconds + "s";
        }
    }

    private String getHelpText() {
        return "=== JVM Command Help ===\n" +
               "Display comprehensive JVM information including:\n" +
               "- Virtual machine details and specifications\n" +
               "- Operating system information\n" +
               "- Runtime information (PID, uptime, paths)\n" +
               "- Class loading statistics\n" +
               "- JIT compilation information\n" +
               "- Memory usage summary\n" +
               "- JVM startup arguments\n\n" +
               "Usage: jvm [--help]\n" +
               "  --help    Show this help message\n";
    }

    @Override
    public String getDescription() {
        return "Display comprehensive JVM information and runtime details";
    }
}
