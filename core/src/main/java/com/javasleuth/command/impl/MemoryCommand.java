package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import java.lang.instrument.Instrumentation;
import java.lang.management.*;
import java.text.DecimalFormat;
import java.util.List;

public class MemoryCommand implements Command {
    private final Instrumentation instrumentation;
    private final MemoryMXBean memoryMXBean;
    private final List<MemoryPoolMXBean> memoryPoolMXBeans;
    private final List<GarbageCollectorMXBean> garbageCollectorMXBeans;
    private final DecimalFormat df = new DecimalFormat("#,##0.00");

    public MemoryCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.memoryPoolMXBeans = ManagementFactory.getMemoryPoolMXBeans();
        this.garbageCollectorMXBeans = ManagementFactory.getGarbageCollectorMXBeans();
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length > 1 && "--help".equals(args[1])) {
            return getHelpText();
        }

        String subCommand = args.length > 1 ? args[1].toLowerCase() : "overview";

        switch (subCommand) {
            case "overview":
            case "all":
                return getMemoryOverview();
            case "pools":
                return getMemoryPools();
            case "gc":
                return getGarbageCollectionInfo();
            case "heap":
                return getHeapInfo();
            case "nonheap":
                return getNonHeapInfo();
            case "direct":
                return getDirectMemoryInfo();
            default:
                return "Unknown subcommand: " + subCommand + ". Use: memory --help for available options";
        }
    }

    private String getMemoryOverview() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Memory Overview ===\n");
        sb.append(getGeneralMemoryInfo()).append("\n");
        sb.append(getMemoryPoolsSummary()).append("\n");
        sb.append(getGarbageCollectionSummary()).append("\n");
        return sb.toString();
    }

    private String getGeneralMemoryInfo() {
        StringBuilder sb = new StringBuilder();
        MemoryUsage heapMemory = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapMemory = memoryMXBean.getNonHeapMemoryUsage();

        sb.append("General Memory Information:\n");
        sb.append("  Heap Memory:\n");
        sb.append("    Used: ").append(formatBytes(heapMemory.getUsed())).append("\n");
        sb.append("    Committed: ").append(formatBytes(heapMemory.getCommitted())).append("\n");
        sb.append("    Max: ").append(formatBytes(heapMemory.getMax())).append("\n");
        if (heapMemory.getMax() > 0) {
            sb.append("    Usage: ").append(String.format("%.2f%%",
                (double) heapMemory.getUsed() / heapMemory.getMax() * 100)).append("\n");
        }

        sb.append("  Non-Heap Memory:\n");
        sb.append("    Used: ").append(formatBytes(nonHeapMemory.getUsed())).append("\n");
        sb.append("    Committed: ").append(formatBytes(nonHeapMemory.getCommitted())).append("\n");
        if (nonHeapMemory.getMax() > 0) {
            sb.append("    Max: ").append(formatBytes(nonHeapMemory.getMax())).append("\n");
            sb.append("    Usage: ").append(String.format("%.2f%%",
                (double) nonHeapMemory.getUsed() / nonHeapMemory.getMax() * 100)).append("\n");
        } else {
            sb.append("    Max: Unlimited\n");
        }

        sb.append("  Objects Pending Finalization: ").append(memoryMXBean.getObjectPendingFinalizationCount()).append("\n");

        return sb.toString();
    }

    private String getMemoryPools() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Memory Pools ===\n");

        if (memoryPoolMXBeans.isEmpty()) {
            sb.append("No memory pool information available\n");
            return sb.toString();
        }

        for (MemoryPoolMXBean poolBean : memoryPoolMXBeans) {
            sb.append("Pool: ").append(poolBean.getName()).append("\n");
            sb.append("  Type: ").append(poolBean.getType()).append("\n");

            MemoryUsage usage = poolBean.getUsage();
            if (usage != null) {
                sb.append("  Current Usage:\n");
                sb.append("    Used: ").append(formatBytes(usage.getUsed())).append("\n");
                sb.append("    Committed: ").append(formatBytes(usage.getCommitted())).append("\n");
                if (usage.getMax() > 0) {
                    sb.append("    Max: ").append(formatBytes(usage.getMax())).append("\n");
                    sb.append("    Usage: ").append(String.format("%.2f%%",
                        (double) usage.getUsed() / usage.getMax() * 100)).append("\n");
                } else {
                    sb.append("    Max: Unlimited\n");
                }
            }

            MemoryUsage peakUsage = poolBean.getPeakUsage();
            if (peakUsage != null) {
                sb.append("  Peak Usage:\n");
                sb.append("    Used: ").append(formatBytes(peakUsage.getUsed())).append("\n");
                sb.append("    Committed: ").append(formatBytes(peakUsage.getCommitted())).append("\n");
                if (peakUsage.getMax() > 0) {
                    sb.append("    Max: ").append(formatBytes(peakUsage.getMax())).append("\n");
                }
            }

            if (poolBean.isCollectionUsageThresholdSupported()) {
                sb.append("  Collection Usage Threshold: ");
                if (poolBean.isCollectionUsageThresholdExceeded()) {
                    sb.append("EXCEEDED (").append(formatBytes(poolBean.getCollectionUsageThreshold())).append(")\n");
                    sb.append("  Collection Usage Threshold Count: ").append(poolBean.getCollectionUsageThresholdCount()).append("\n");
                } else {
                    sb.append("Not exceeded (").append(formatBytes(poolBean.getCollectionUsageThreshold())).append(")\n");
                }
            }

            if (poolBean.isUsageThresholdSupported()) {
                sb.append("  Usage Threshold: ");
                if (poolBean.isUsageThresholdExceeded()) {
                    sb.append("EXCEEDED (").append(formatBytes(poolBean.getUsageThreshold())).append(")\n");
                    sb.append("  Usage Threshold Count: ").append(poolBean.getUsageThresholdCount()).append("\n");
                } else {
                    sb.append("Not exceeded (").append(formatBytes(poolBean.getUsageThreshold())).append(")\n");
                }
            }

            String[] managers = poolBean.getMemoryManagerNames();
            if (managers.length > 0) {
                sb.append("  Memory Managers: ");
                for (int i = 0; i < managers.length; i++) {
                    sb.append(managers[i]);
                    if (i < managers.length - 1) sb.append(", ");
                }
                sb.append("\n");
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    private String getMemoryPoolsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Memory Pools Summary:\n");

        if (memoryPoolMXBeans.isEmpty()) {
            sb.append("  No memory pool information available\n");
            return sb.toString();
        }

        for (MemoryPoolMXBean poolBean : memoryPoolMXBeans) {
            MemoryUsage usage = poolBean.getUsage();
            if (usage != null) {
                String usagePercent = "N/A";
                if (usage.getMax() > 0) {
                    usagePercent = String.format("%.1f%%", (double) usage.getUsed() / usage.getMax() * 100);
                }
                sb.append(String.format("  %-25s: %s / %s (%s)\n",
                    poolBean.getName(),
                    formatBytes(usage.getUsed()),
                    usage.getMax() > 0 ? formatBytes(usage.getMax()) : "unlimited",
                    usagePercent));
            }
        }

        return sb.toString();
    }

    private String getGarbageCollectionInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Garbage Collection Information ===\n");

        if (garbageCollectorMXBeans.isEmpty()) {
            sb.append("No garbage collector information available\n");
            return sb.toString();
        }

        for (GarbageCollectorMXBean gcBean : garbageCollectorMXBeans) {
            sb.append("Garbage Collector: ").append(gcBean.getName()).append("\n");
            sb.append("  Valid: ").append(gcBean.isValid()).append("\n");
            sb.append("  Collection Count: ").append(gcBean.getCollectionCount()).append("\n");
            sb.append("  Collection Time: ").append(gcBean.getCollectionTime()).append(" ms\n");

            if (gcBean.getCollectionCount() > 0) {
                double avgTime = (double) gcBean.getCollectionTime() / gcBean.getCollectionCount();
                sb.append("  Average Collection Time: ").append(String.format("%.2f ms", avgTime)).append("\n");
            }

            String[] poolNames = gcBean.getMemoryPoolNames();
            if (poolNames.length > 0) {
                sb.append("  Memory Pools: ");
                for (int i = 0; i < poolNames.length; i++) {
                    sb.append(poolNames[i]);
                    if (i < poolNames.length - 1) sb.append(", ");
                }
                sb.append("\n");
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    private String getGarbageCollectionSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Garbage Collection Summary:\n");

        if (garbageCollectorMXBeans.isEmpty()) {
            sb.append("  No garbage collector information available\n");
            return sb.toString();
        }

        long totalCollections = 0;
        long totalTime = 0;

        for (GarbageCollectorMXBean gcBean : garbageCollectorMXBeans) {
            totalCollections += gcBean.getCollectionCount();
            totalTime += gcBean.getCollectionTime();

            sb.append(String.format("  %-20s: %d collections, %d ms total\n",
                gcBean.getName(), gcBean.getCollectionCount(), gcBean.getCollectionTime()));
        }

        sb.append(String.format("  Total: %d collections, %d ms total\n", totalCollections, totalTime));
        if (totalCollections > 0) {
            sb.append(String.format("  Average GC Time: %.2f ms per collection\n",
                (double) totalTime / totalCollections));
        }

        return sb.toString();
    }

    private String getHeapInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Heap Memory Details ===\n");

        MemoryUsage heapMemory = memoryMXBean.getHeapMemoryUsage();
        sb.append("Overall Heap Usage:\n");
        sb.append(formatMemoryUsageDetailed(heapMemory)).append("\n");

        sb.append("Heap Memory Pools:\n");
        for (MemoryPoolMXBean poolBean : memoryPoolMXBeans) {
            if (poolBean.getType() == MemoryType.HEAP) {
                MemoryUsage usage = poolBean.getUsage();
                if (usage != null) {
                    sb.append("  ").append(poolBean.getName()).append(":\n");
                    sb.append("    ").append(formatMemoryUsageDetailed(usage)).append("\n");
                }
            }
        }

        return sb.toString();
    }

    private String getNonHeapInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Non-Heap Memory Details ===\n");

        MemoryUsage nonHeapMemory = memoryMXBean.getNonHeapMemoryUsage();
        sb.append("Overall Non-Heap Usage:\n");
        sb.append(formatMemoryUsageDetailed(nonHeapMemory)).append("\n");

        sb.append("Non-Heap Memory Pools:\n");
        for (MemoryPoolMXBean poolBean : memoryPoolMXBeans) {
            if (poolBean.getType() == MemoryType.NON_HEAP) {
                MemoryUsage usage = poolBean.getUsage();
                if (usage != null) {
                    sb.append("  ").append(poolBean.getName()).append(":\n");
                    sb.append("    ").append(formatMemoryUsageDetailed(usage)).append("\n");
                }
            }
        }

        return sb.toString();
    }

    private String getDirectMemoryInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Direct Memory Information ===\n");

        try {
            // Use reflection to access direct memory information
            Class<?> vmClass = Class.forName("sun.misc.VM");
            long maxDirectMemory = (Long) vmClass.getMethod("maxDirectMemory").invoke(null);
            sb.append("Max Direct Memory: ").append(formatBytes(maxDirectMemory)).append("\n");

            // Try to get used direct memory (this might not be available in all JVMs)
            try {
                Class<?> bitsClass = Class.forName("java.nio.Bits");
                long reservedMemory = (Long) bitsClass.getDeclaredMethod("reservedMemory").invoke(null);
                sb.append("Reserved Direct Memory: ").append(formatBytes(reservedMemory)).append("\n");
            } catch (Exception e) {
                sb.append("Reserved Direct Memory: Not available\n");
            }

        } catch (Exception e) {
            sb.append("Direct memory information not available: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    private String formatMemoryUsageDetailed(MemoryUsage usage) {
        StringBuilder sb = new StringBuilder();
        sb.append("Used: ").append(formatBytes(usage.getUsed()));
        sb.append(", Committed: ").append(formatBytes(usage.getCommitted()));
        if (usage.getMax() > 0) {
            sb.append(", Max: ").append(formatBytes(usage.getMax()));
            sb.append(String.format(" (%.2f%% used)", (double) usage.getUsed() / usage.getMax() * 100));
        } else {
            sb.append(", Max: Unlimited");
        }
        sb.append(", Initial: ").append(formatBytes(usage.getInit()));
        return sb.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return df.format(bytes / 1024.0) + " KB";
        if (bytes < 1024 * 1024 * 1024) return df.format(bytes / (1024.0 * 1024.0)) + " MB";
        return df.format(bytes / (1024.0 * 1024.0 * 1024.0)) + " GB";
    }

    private String getHelpText() {
        return "=== Memory Command Help ===\n" +
               "Display detailed memory information and statistics\n\n" +
               "Usage:\n" +
               "  memory [subcommand]        Display memory information\n" +
               "  memory --help              Show this help message\n\n" +
               "Subcommands:\n" +
               "  overview (default)         Show comprehensive memory overview\n" +
               "  all                        Same as overview\n" +
               "  pools                      Show detailed memory pool information\n" +
               "  gc                         Show detailed garbage collection information\n" +
               "  heap                       Show heap memory details only\n" +
               "  nonheap                    Show non-heap memory details only\n" +
               "  direct                     Show direct memory information\n\n" +
               "Examples:\n" +
               "  memory                     Show memory overview\n" +
               "  memory pools               Show all memory pools\n" +
               "  memory gc                  Show GC statistics\n" +
               "  memory heap                Show heap memory details\n\n" +
               "Memory Information Includes:\n" +
               "- Heap and non-heap memory usage\n" +
               "- Individual memory pool statistics\n" +
               "- Garbage collection performance\n" +
               "- Memory thresholds and limits\n" +
               "- Direct memory usage (if available)\n";
    }

    @Override
    public String getDescription() {
        return "Display detailed memory information and statistics";
    }
}
