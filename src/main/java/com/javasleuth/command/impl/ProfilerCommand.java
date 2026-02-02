package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.util.PerformanceOptimizer;
import com.javasleuth.util.StringUtils;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * ProfilerCommand provides basic performance profiling capabilities
 *
 * 说明：当前实现基于 ThreadMXBean/JMX 的采样统计，不依赖也不内置 async-profiler（native）。
 * 如需更精确的火焰图或更低开销的采样，请在生产环境使用外部专业工具（例如 async-profiler）。
 *
 * Usage:
 * profiler start [type] [duration] - Start profiling
 * profiler stop - Stop profiling and generate report
 * profiler status - Show profiling status
 * profiler report - Generate profiling report
 * profiler clear - Clear profiling data
 */
public class ProfilerCommand implements Command {
    private final Instrumentation instrumentation;
    private final ThreadMXBean threadMXBean;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Object> profilingResults = new ConcurrentHashMap<>();
    private final AtomicLong sampleCount = new AtomicLong(0);
    private final ConcurrentHashMap<String, ProfileSample> profileData = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private long startTime;
    private String currentType = "cpu";
    private int intervalMs = 100; // Default 100ms sampling interval

    public ProfilerCommand(Instrumentation instrumentation) {
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
            case "start":
                return startProfiling(args);
            case "stop":
                return stopProfiling();
            case "status":
                return getProfilingStatus();
            case "report":
                return generateReport(args);
            case "clear":
                return clearProfileData();
            default:
                return "Unknown profiler action: " + action + "\n" + getUsage();
        }
    }

    private String startProfiling(String[] args) throws Exception {
        if (isRunning.get()) {
            return "Profiler is already running. Stop it first with 'profiler stop'";
        }

        // Parse arguments
        String type = args.length > 2 ? args[2] : "cpu";
        int duration = args.length > 3 ? Integer.parseInt(args[3]) : 60; // Default 60 seconds
        int interval = args.length > 4 ? Integer.parseInt(args[4]) : 100; // Default 100ms

        currentType = type;
        intervalMs = interval;

        try {
            // Clear previous data
            profileData.clear();
            sampleCount.set(0);

            isRunning.set(true);
            startTime = System.currentTimeMillis();

            // Start sampling
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "profiler-sampler");
                t.setDaemon(true);
                return t;
            });

            scheduler.scheduleAtFixedRate(this::sampleProfile, 0, intervalMs, TimeUnit.MILLISECONDS);

            // Schedule automatic stop if duration is specified
            if (duration > 0) {
                scheduler.schedule(() -> {
                    try {
                        stopProfiling();
                    } catch (Exception e) {
                        System.err.println("Error auto-stopping profiler: " + e.getMessage());
                    }
                }, duration, TimeUnit.SECONDS);
            }

            return String.format("Started %s profiling with %dms interval\n" +
                               "Duration: %s\n" +
                               "Use 'profiler status' to check progress\n" +
                               "Use 'profiler stop' to stop and get results",
                               type, intervalMs,
                               duration > 0 ? duration + " seconds" : "unlimited");

        } catch (Exception e) {
            isRunning.set(false);
            return "Failed to start profiler: " + e.getMessage();
        }
    }

    private String stopProfiling() throws Exception {
        if (!isRunning.get()) {
            return "Profiler is not running";
        }

        try {
            isRunning.set(false);
            if (scheduler != null) {
                scheduler.shutdown();
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            }

            long duration = System.currentTimeMillis() - startTime;
            long samples = sampleCount.get();

            // Store results for future reference
            profilingResults.put("lastDuration", duration);
            profilingResults.put("lastSamples", samples);
            profilingResults.put("lastType", currentType);

            return String.format("Profiling stopped after %s\n" +
                               "Type: %s\n" +
                               "Samples collected: %d\n" +
                               "Unique stack traces: %d\n" +
                               "Use 'profiler report' for detailed analysis",
                               PerformanceOptimizer.formatDuration(duration),
                               currentType,
                               samples,
                               profileData.size());

        } catch (Exception e) {
            isRunning.set(false);
            return "Failed to stop profiler: " + e.getMessage();
        }
    }

    private String getProfilingStatus() {
        if (isRunning.get()) {
            long elapsed = System.currentTimeMillis() - startTime;
            long samples = sampleCount.get();
            return String.format("Profiler Status: RUNNING\n" +
                               "Type: %s\n" +
                               "Interval: %dms\n" +
                               "Elapsed: %s\n" +
                               "Samples collected: %d\n" +
                               "Unique traces: %d\n" +
                               "Use 'profiler stop' to stop profiling",
                               currentType,
                               intervalMs,
                               PerformanceOptimizer.formatDuration(elapsed),
                               samples,
                               profileData.size());
        } else {
            Object lastDuration = profilingResults.get("lastDuration");
            if (lastDuration != null) {
                Object lastSamples = profilingResults.get("lastSamples");
                Object lastType = profilingResults.get("lastType");
                return String.format("Profiler Status: STOPPED\n" +
                                   "Last session:\n" +
                                   "  Duration: %s\n" +
                                   "  Samples: %d\n" +
                                   "  Type: %s\n" +
                                   "  Traces: %d",
                                   PerformanceOptimizer.formatDuration((Long) lastDuration),
                                   lastSamples,
                                   lastType,
                                   profileData.size());
            } else {
                return "Profiler Status: STOPPED (No previous sessions)";
            }
        }
    }

    private String generateReport(String[] args) {
        if (profileData.isEmpty()) {
            return "No profiling data available. Start profiling with 'profiler start'";
        }

        int limit = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        String format = args.length > 3 ? args[3].toLowerCase() : "summary";

        switch (format) {
            case "summary":
                return generateSummaryReport(limit);
            case "detailed":
                return generateDetailedReport(limit);
            case "flamegraph":
                return generateFlameGraphData(limit);
            case "file":
                return generateFileReport(limit);
            default:
                return "Unknown report format: " + format + ". Use: summary, detailed, flamegraph, file";
        }
    }

    private String clearProfileData() {
        int samples = profileData.size();
        long count = sampleCount.get();

        profileData.clear();
        sampleCount.set(0);
        profilingResults.clear();

        return String.format("Cleared %d profile samples (%d total samples)", samples, count);
    }

    private void sampleProfile() {
        try {
            long[] threadIds = threadMXBean.getAllThreadIds();
            ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds, 10); // Top 10 stack frames

            for (ThreadInfo threadInfo : threadInfos) {
                if (threadInfo != null && threadInfo.getStackTrace().length > 0) {
                    String stackSignature = generateStackSignature(threadInfo);
                    profileData.computeIfAbsent(stackSignature, k -> new ProfileSample(threadInfo))
                             .incrementCount();
                }
            }

            sampleCount.incrementAndGet();
        } catch (Exception e) {
            // Ignore sampling errors to avoid disrupting the profiled application
        }
    }

    private String generateStackSignature(ThreadInfo threadInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append(threadInfo.getThreadName()).append(":").append(threadInfo.getThreadState()).append(":");

        StackTraceElement[] stackTrace = threadInfo.getStackTrace();
        int limit = Math.min(stackTrace.length, 5); // Top 5 frames for signature

        for (int i = 0; i < limit; i++) {
            StackTraceElement element = stackTrace[i];
            sb.append(element.getClassName()).append(".").append(element.getMethodName()).append(";");
        }

        return sb.toString();
    }

    private String generateSummaryReport(int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Profiling Summary Report ===\n");
        sb.append("Total samples: ").append(sampleCount.get()).append("\n");
        sb.append("Unique stack traces: ").append(profileData.size()).append("\n\n");

        // Sort by sample count
        List<Map.Entry<String, ProfileSample>> sortedEntries = profileData.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getCount(), a.getValue().getCount()))
            .limit(limit)
            .collect(Collectors.toList());

        sb.append("Top ").append(limit).append(" Hot Spots:\n");
        sb.append(String.format("%-60s %-10s %-10s\n", "Method", "Samples", "Percent"));
        sb.append(StringUtils.repeat('-', 80)).append("\n");

        long totalSamples = sampleCount.get();
        for (Map.Entry<String, ProfileSample> entry : sortedEntries) {
            ProfileSample sample = entry.getValue();
            double percentage = (double) sample.getCount() / totalSamples * 100;
            sb.append(String.format("%-60s %-10d %-10.2f%%\n",
                      truncate(sample.getTopMethod(), 60),
                      sample.getCount(),
                      percentage));
        }

        return sb.toString();
    }

    private String generateDetailedReport(int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Detailed Profiling Report ===\n");
        sb.append("Total samples: ").append(sampleCount.get()).append("\n");
        sb.append("Sample interval: ").append(intervalMs).append("ms\n");
        sb.append("Profiling type: ").append(currentType).append("\n\n");

        List<Map.Entry<String, ProfileSample>> sortedEntries = profileData.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getCount(), a.getValue().getCount()))
            .limit(limit)
            .collect(Collectors.toList());

        long totalSamples = sampleCount.get();
        for (int i = 0; i < sortedEntries.size(); i++) {
            Map.Entry<String, ProfileSample> entry = sortedEntries.get(i);
            ProfileSample sample = entry.getValue();
            double percentage = (double) sample.getCount() / totalSamples * 100;

            sb.append("--- Hot Spot #").append(i + 1).append(" (").append(sample.getCount())
              .append(" samples, ").append(String.format("%.2f%%", percentage)).append(") ---\n");
            sb.append("Thread: ").append(sample.getThreadName()).append("\n");
            sb.append("State: ").append(sample.getThreadState()).append("\n");
            sb.append("Stack Trace:\n");

            StackTraceElement[] stackTrace = sample.getStackTrace();
            for (StackTraceElement element : stackTrace) {
                sb.append("  at ").append(element.toString()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String generateFlameGraphData(int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Flame Graph Data (simplified format)\n");
        sb.append("# Use with flame graph tools for visualization\n\n");

        List<Map.Entry<String, ProfileSample>> sortedEntries = profileData.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getCount(), a.getValue().getCount()))
            .limit(limit)
            .collect(Collectors.toList());

        for (Map.Entry<String, ProfileSample> entry : sortedEntries) {
            ProfileSample sample = entry.getValue();
            StackTraceElement[] stackTrace = sample.getStackTrace();

            // Build flame graph stack representation
            StringBuilder stackBuilder = new StringBuilder();
            for (int i = stackTrace.length - 1; i >= 0; i--) {
                StackTraceElement element = stackTrace[i];
                if (stackBuilder.length() > 0) {
                    stackBuilder.append(";");
                }
                stackBuilder.append(element.getClassName()).append(".").append(element.getMethodName());
            }

            sb.append(stackBuilder.toString()).append(" ").append(sample.getCount()).append("\n");
        }

        return sb.toString();
    }

    private String generateFileReport(int limit) {
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            String reportFile = tempDir + File.separator + "java-sleuth-profile-" +
                              System.currentTimeMillis() + ".txt";

            try (FileWriter writer = new FileWriter(reportFile)) {
                writer.write(generateDetailedReport(limit));
            }

            return "Detailed report written to: " + reportFile;
        } catch (IOException e) {
            return "Failed to write report file: " + e.getMessage();
        }
    }

    private String truncate(String str, int maxLength) {
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }

    private String getUsage() {
        return "Profiler Command Usage:\n" +
               "  profiler start [type] [duration] [interval] - Start profiling\n" +
               "  profiler stop                               - Stop profiling\n" +
               "  profiler status                             - Show profiling status\n" +
               "  profiler report [limit] [format]            - Generate profiling report\n" +
               "  profiler clear                              - Clear profiling data\n" +
               "\n" +
               "Parameters:\n" +
               "  type     - Profiling type (cpu, memory, etc.) - currently all use sampling\n" +
               "  duration - Duration in seconds (0 for unlimited)\n" +
               "  interval - Sampling interval in milliseconds (default: 100)\n" +
               "  limit    - Number of top entries to show (default: 20)\n" +
               "  format   - Report format: summary, detailed, flamegraph, file\n" +
               "\n" +
               "Examples:\n" +
               "  profiler start                              - Start CPU profiling for 60 seconds\n" +
               "  profiler start cpu 30 50                    - Start profiling for 30s, 50ms interval\n" +
               "  profiler report 10 detailed                 - Show top 10 detailed entries";
    }

    @Override
    public String getDescription() {
        return "Basic performance profiling using JMX sampling";
    }

    // Inner class to store profile sample data
    private static class ProfileSample {
        private final ThreadInfo threadInfo;
        private final String threadName;
        private final Thread.State threadState;
        private final StackTraceElement[] stackTrace;
        private final String topMethod;
        private final AtomicLong count = new AtomicLong(1);

        public ProfileSample(ThreadInfo threadInfo) {
            this.threadInfo = threadInfo;
            this.threadName = threadInfo.getThreadName();
            this.threadState = threadInfo.getThreadState();
            this.stackTrace = threadInfo.getStackTrace();

            // Extract top method for display
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

        public String getThreadName() {
            return threadName;
        }

        public Thread.State getThreadState() {
            return threadState;
        }

        public StackTraceElement[] getStackTrace() {
            return stackTrace;
        }

        public String getTopMethod() {
            return topMethod;
        }
    }
}
