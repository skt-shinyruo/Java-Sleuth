package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * StackCommand provides advanced stack trace monitoring and analysis
 *
 * Usage:
 * stack dump [thread-id] - Dump stack traces
 * stack monitor start - Start continuous stack monitoring
 * stack monitor stop - Stop stack monitoring
 * stack analyze - Analyze stack patterns
 * stack blocked - Show blocked threads
 * stack deadlock - Check for deadlocks
 * stack hot - Show hottest stack traces
 */
public class StackCommand implements Command {
    private final Instrumentation instrumentation;
    private final ThreadMXBean threadMXBean;
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, StackTraceStats> stackStats = new ConcurrentHashMap<>();
    private final AtomicLong samplingCount = new AtomicLong(0);
    private Thread monitoringThread;

    public StackCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        this.threadMXBean = ManagementFactory.getThreadMXBean();
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length < 2) {
            return getUsage();
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "dump":
                return dumpStackTraces(args);
            case "monitor":
                return handleMonitoring(args);
            case "analyze":
                return analyzeStackPatterns(args);
            case "blocked":
                return showBlockedThreads();
            case "deadlock":
                return checkDeadlocks();
            case "hot":
                return showHotStackTraces(args);
            case "stats":
                return showStackStats();
            case "clear":
                return clearStackData();
            default:
                return "Unknown stack action: " + action + "\n" + getUsage();
        }
    }

    private String dumpStackTraces(String[] args) {
        try {
            if (args.length > 2) {
                // Dump specific thread
                long threadId = Long.parseLong(args[2]);
                ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId, Integer.MAX_VALUE);
                if (threadInfo == null) {
                    return "Thread not found: " + threadId;
                }
                return formatThreadInfo(threadInfo, true);
            } else {
                // Dump all threads
                StringBuilder sb = new StringBuilder();
                ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);

                sb.append("=== Thread Dump ===\n");
                sb.append("Total threads: ").append(threadInfos.length).append("\n");
                sb.append("Timestamp: ").append(new Date()).append("\n\n");

                for (ThreadInfo threadInfo : threadInfos) {
                    sb.append(formatThreadInfo(threadInfo, false)).append("\n");
                }

                return sb.toString();
            }
        } catch (NumberFormatException e) {
            return "Invalid thread ID: " + args[2];
        } catch (Exception e) {
            return "Failed to dump stack traces: " + e.getMessage();
        }
    }

    private String handleMonitoring(String[] args) {
        if (args.length < 3) {
            return "Usage: stack monitor [start|stop|status]";
        }

        String action = args[2].toLowerCase();

        switch (action) {
            case "start":
                return startStackMonitoring(args);
            case "stop":
                return stopStackMonitoring();
            case "status":
                return getMonitoringStatus();
            default:
                return "Unknown monitor action: " + action;
        }
    }

    private String startStackMonitoring(String[] args) {
        if (monitoring.get()) {
            return "Stack monitoring is already running";
        }

        int intervalMs = args.length > 3 ? Integer.parseInt(args[3]) : 1000; // Default 1 second

        monitoring.set(true);
        samplingCount.set(0);
        stackStats.clear();

        monitoringThread = new Thread(() -> {
            while (monitoring.get()) {
                try {
                    sampleStackTraces();
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    System.err.println("Error in stack monitoring: " + e.getMessage());
                }
            }
        }, "stack-monitor");

        monitoringThread.setDaemon(true);
        monitoringThread.start();

        return String.format("Started stack monitoring with %dms interval\n" +
                           "Use 'stack monitor stop' to stop monitoring\n" +
                           "Use 'stack analyze' to view collected data",
                           intervalMs);
    }

    private String stopStackMonitoring() {
        if (!monitoring.get()) {
            return "Stack monitoring is not running";
        }

        monitoring.set(false);
        if (monitoringThread != null) {
            monitoringThread.interrupt();
        }

        long totalSamples = samplingCount.get();
        return String.format("Stopped stack monitoring\n" +
                           "Total samples collected: %d\n" +
                           "Unique stack patterns: %d\n" +
                           "Use 'stack analyze' to view results",
                           totalSamples, stackStats.size());
    }

    private String getMonitoringStatus() {
        if (monitoring.get()) {
            return String.format("Stack monitoring: ACTIVE\n" +
                               "Samples collected: %d\n" +
                               "Unique patterns: %d\n" +
                               "Thread: %s",
                               samplingCount.get(),
                               stackStats.size(),
                               monitoringThread != null ? monitoringThread.getName() : "unknown");
        } else {
            return String.format("Stack monitoring: INACTIVE\n" +
                               "Last session samples: %d\n" +
                               "Stored patterns: %d",
                               samplingCount.get(),
                               stackStats.size());
        }
    }

    private void sampleStackTraces() {
        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds(), 20); // Top 20 stack frames

        for (ThreadInfo threadInfo : threadInfos) {
            if (threadInfo != null && threadInfo.getStackTrace().length > 0) {
                String stackSignature = generateStackSignature(threadInfo);
                stackStats.computeIfAbsent(stackSignature, k -> new StackTraceStats(threadInfo))
                         .incrementCount();
            }
        }

        samplingCount.incrementAndGet();
    }

    private String generateStackSignature(ThreadInfo threadInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append(threadInfo.getThreadName()).append(":");
        sb.append(threadInfo.getThreadState()).append(":");

        StackTraceElement[] stackTrace = threadInfo.getStackTrace();
        int limit = Math.min(stackTrace.length, 5); // Top 5 frames for signature

        for (int i = 0; i < limit; i++) {
            StackTraceElement element = stackTrace[i];
            sb.append(element.getClassName()).append(".").append(element.getMethodName()).append(";");
        }

        return sb.toString();
    }

    private String analyzeStackPatterns(String[] args) {
        if (stackStats.isEmpty()) {
            return "No stack trace data available. Start monitoring with 'stack monitor start'";
        }

        int limit = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        StringBuilder sb = new StringBuilder();
        sb.append("=== Stack Pattern Analysis ===\n");
        sb.append("Total samples: ").append(samplingCount.get()).append("\n");
        sb.append("Unique patterns: ").append(stackStats.size()).append("\n\n");

        // Sort by frequency
        List<StackTraceStats> sortedStats = stackStats.values().stream()
            .sorted((a, b) -> Long.compare(b.getCount(), a.getCount()))
            .limit(limit)
            .collect(Collectors.toList());

        sb.append("Top ").append(limit).append(" Stack Patterns by Frequency:\n");
        sb.append(String.format("%-60s %-10s %-10s %-15s\n",
                  "Pattern", "Count", "Percent", "Thread State"));
        sb.append("-".repeat(100)).append("\n");

        long totalSamples = samplingCount.get();
        for (StackTraceStats stats : sortedStats) {
            double percentage = (double) stats.getCount() / totalSamples * 100;
            sb.append(String.format("%-60s %-10d %-10.2f%% %-15s\n",
                      truncate(stats.getTopMethod(), 60),
                      stats.getCount(),
                      percentage,
                      stats.getThreadState()));
        }

        return sb.toString();
    }

    private String showBlockedThreads() {
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
        List<ThreadInfo> blockedThreads = new ArrayList<>();

        for (ThreadInfo threadInfo : threadInfos) {
            if (threadInfo.getThreadState() == Thread.State.BLOCKED ||
                threadInfo.getThreadState() == Thread.State.WAITING ||
                threadInfo.getThreadState() == Thread.State.TIMED_WAITING) {
                blockedThreads.add(threadInfo);
            }
        }

        if (blockedThreads.isEmpty()) {
            return "No blocked threads found";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Blocked Threads ===\n");
        sb.append("Found ").append(blockedThreads.size()).append(" blocked threads:\n\n");

        for (ThreadInfo threadInfo : blockedThreads) {
            sb.append(formatThreadInfo(threadInfo, true)).append("\n");
        }

        return sb.toString();
    }

    private String checkDeadlocks() {
        long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();

        if (deadlockedThreads == null || deadlockedThreads.length == 0) {
            return "No deadlocks detected";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("⚠️  DEADLOCK DETECTED! ⚠️\n");
        sb.append("Deadlocked threads: ").append(deadlockedThreads.length).append("\n\n");

        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(deadlockedThreads);
        for (ThreadInfo threadInfo : threadInfos) {
            sb.append(formatThreadInfo(threadInfo, true)).append("\n");
        }

        return sb.toString();
    }

    private String showHotStackTraces(String[] args) {
        if (stackStats.isEmpty()) {
            return "No stack trace data available. Start monitoring with 'stack monitor start'";
        }

        int limit = args.length > 2 ? Integer.parseInt(args[2]) : 5;

        StringBuilder sb = new StringBuilder();
        sb.append("=== Hottest Stack Traces ===\n");

        List<StackTraceStats> hotStacks = stackStats.values().stream()
            .sorted((a, b) -> Long.compare(b.getCount(), a.getCount()))
            .limit(limit)
            .collect(Collectors.toList());

        for (int i = 0; i < hotStacks.size(); i++) {
            StackTraceStats stats = hotStacks.get(i);
            sb.append("--- Hot Stack #").append(i + 1).append(" (").append(stats.getCount()).append(" samples) ---\n");
            sb.append(formatStackTrace(stats.getSampleThreadInfo())).append("\n");
        }

        return sb.toString();
    }

    private String showStackStats() {
        if (stackStats.isEmpty()) {
            return "No stack statistics available";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Stack Statistics ===\n");
        sb.append("Total samples: ").append(samplingCount.get()).append("\n");
        sb.append("Unique patterns: ").append(stackStats.size()).append("\n");
        sb.append("Monitoring active: ").append(monitoring.get() ? "YES" : "NO").append("\n\n");

        // Group by thread state
        Map<Thread.State, Long> stateStats = stackStats.values().stream()
            .collect(Collectors.groupingBy(
                stats -> stats.getThreadState(),
                Collectors.summingLong(StackTraceStats::getCount)
            ));

        sb.append("Thread State Distribution:\n");
        for (Map.Entry<Thread.State, Long> entry : stateStats.entrySet()) {
            double percentage = (double) entry.getValue() / samplingCount.get() * 100;
            sb.append(String.format("  %-15s: %6d samples (%.2f%%)\n",
                      entry.getKey(), entry.getValue(), percentage));
        }

        return sb.toString();
    }

    private String clearStackData() {
        int patterns = stackStats.size();
        long samples = samplingCount.get();

        stackStats.clear();
        samplingCount.set(0);

        return String.format("Cleared %d stack patterns and %d samples", patterns, samples);
    }

    private String formatThreadInfo(ThreadInfo threadInfo, boolean includeStackTrace) {
        StringBuilder sb = new StringBuilder();

        sb.append("Thread: ").append(threadInfo.getThreadName())
          .append(" (ID: ").append(threadInfo.getThreadId()).append(")\n");
        sb.append("State: ").append(threadInfo.getThreadState());

        if (threadInfo.getLockName() != null) {
            sb.append(" on ").append(threadInfo.getLockName());
        }
        if (threadInfo.getLockOwnerName() != null) {
            sb.append(" owned by ").append(threadInfo.getLockOwnerName());
        }
        sb.append("\n");

        if (includeStackTrace) {
            sb.append(formatStackTrace(threadInfo));
        }

        return sb.toString();
    }

    private String formatStackTrace(ThreadInfo threadInfo) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] stackTrace = threadInfo.getStackTrace();

        for (StackTraceElement element : stackTrace) {
            sb.append("    at ").append(element.toString()).append("\n");
        }

        return sb.toString();
    }

    private String truncate(String str, int maxLength) {
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }

    private String getUsage() {
        return "Stack Command Usage:\n" +
               "  stack dump [thread-id]          - Dump stack traces (all threads or specific)\n" +
               "  stack monitor start [interval]  - Start continuous monitoring (default 1000ms)\n" +
               "  stack monitor stop              - Stop monitoring\n" +
               "  stack monitor status            - Show monitoring status\n" +
               "  stack analyze [limit]           - Analyze collected stack patterns\n" +
               "  stack blocked                   - Show blocked/waiting threads\n" +
               "  stack deadlock                  - Check for deadlocks\n" +
               "  stack hot [limit]               - Show hottest stack traces\n" +
               "  stack stats                     - Show stack statistics\n" +
               "  stack clear                     - Clear collected data\n" +
               "\n" +
               "Examples:\n" +
               "  stack dump                      - Dump all thread stacks\n" +
               "  stack dump 123                  - Dump stack for thread ID 123\n" +
               "  stack monitor start 500         - Monitor every 500ms\n" +
               "  stack analyze 20                - Show top 20 stack patterns";
    }

    @Override
    public String getDescription() {
        return "Advanced stack trace monitoring and analysis";
    }

    // Inner class to store stack trace statistics
    private static class StackTraceStats {
        private final ThreadInfo sampleThreadInfo;
        private final Thread.State threadState;
        private final String topMethod;
        private final AtomicLong count = new AtomicLong(1);

        public StackTraceStats(ThreadInfo threadInfo) {
            this.sampleThreadInfo = threadInfo;
            this.threadState = threadInfo.getThreadState();

            // Extract top method for display
            StackTraceElement[] stackTrace = threadInfo.getStackTrace();
            if (stackTrace.length > 0) {
                StackTraceElement top = stackTrace[0];
                this.topMethod = top.getClassName() + "." + top.getMethodName();
            } else {
                this.topMethod = "No stack trace available";
            }
        }

        public void incrementCount() {
            count.incrementAndGet();
        }

        public long getCount() {
            return count.get();
        }

        public ThreadInfo getSampleThreadInfo() {
            return sampleThreadInfo;
        }

        public Thread.State getThreadState() {
            return threadState;
        }

        public String getTopMethod() {
            return topMethod;
        }
    }
}