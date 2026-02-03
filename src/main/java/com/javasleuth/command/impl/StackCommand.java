package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.command.JobManager;
import com.javasleuth.command.CommandContext;
import com.javasleuth.command.CommandContextHolder;
import com.javasleuth.command.StreamCommand;
import com.javasleuth.command.StreamSink;
import com.javasleuth.command.session.ClientSession;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.data.StackTraceResult;
import com.javasleuth.enhancement.ClassEnhancer;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.enhancement.StackEnhancer;
import com.javasleuth.monitor.StackInterceptor;
import com.javasleuth.util.WildcardMatcher;
import com.javasleuth.util.StringUtils;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.time.LocalTime;
import java.util.UUID;

/**
 * StackCommand 提供两类能力：
 * 1) 线程栈采样/分析（原有能力，stack monitor/dump/analyze/...）
 * 2) 方法触发栈追踪（Arthas 风格简化版）：stack <class-pattern> <method-pattern> [options]
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
public class StackCommand implements StreamCommand {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ProductionConfig config;
    private final ThreadMXBean threadMXBean;
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, StackTraceStats> stackStats = new ConcurrentHashMap<>();
    private final AtomicLong samplingCount = new AtomicLong(0);
    private Thread monitoringThread;

    private final ConcurrentHashMap<String, StackSession> activeSessions = new ConcurrentHashMap<>();

    public StackCommand(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = ProductionConfig.getInstance();
        this.threadMXBean = ManagementFactory.getThreadMXBean();
    }

    @Override
    public String execute(String[] args) throws Exception {
        return runStack(args, null);
    }

    @Override
    public void executeStream(String[] args, StreamSink sink) throws Exception {
        runStack(args, sink);
    }

    private String runStack(String[] args, StreamSink sink) throws Exception {
        if (args == null || args.length < 2) {
            return getUsage();
        }

        String sub = args[1];
        if ("-h".equals(sub) || "--help".equals(sub) || "help".equalsIgnoreCase(sub)) {
            return getUsage();
        }

        String action = sub.toLowerCase(Locale.ROOT);
        if (isLegacyAction(action)) {
            // 兼容原有线程栈采样/分析命令（非 streaming 模式）
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

        // Arthas-like: stack <class-pattern> <method-pattern> [options]
        if (args.length < 3) {
            return getTraceHelp();
        }

        boolean background = false;
        String classPattern = args[1];
        String methodPattern = args[2];

        int maxCount = 10;
        long timeoutSeconds = 30;
        int depth = 20;

        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if ("-n".equals(a) || "--count".equals(a)) {
                if (i + 1 < args.length) {
                    maxCount = parseInt(args[++i], 10);
                }
            } else if ("-t".equals(a) || "--timeout".equals(a)) {
                if (i + 1 < args.length) {
                    timeoutSeconds = parseLong(args[++i], 30);
                }
            } else if ("--depth".equals(a)) {
                if (i + 1 < args.length) {
                    depth = parseInt(args[++i], 20);
                }
            } else if ("--bg".equals(a)) {
                background = true;
            } else if ("-h".equals(a) || "--help".equals(a)) {
                return getTraceHelp();
            }
        }

        if (timeoutSeconds <= 0) {
            timeoutSeconds = 30;
        }
        if (maxCount <= 0) {
            maxCount = 1;
        }
        depth = Math.max(1, Math.min(depth, 200));

        if (background) {
            String[] jobArgs = removeFlag(args, "--bg");
            String commandLine = String.join(" ", jobArgs);
            String jobId = JobManager.getInstance().submitStreamJob(
                "stack",
                commandLine,
                jobSink -> runStack(jobArgs, jobSink)
            );
            String msg = "Started stack in background. Job ID: " + jobId + " (use: jobs tail " + jobId + ")";
            if (sink != null) {
                sink.send(msg);
                sink.close("job started");
                return "";
            }
            return msg;
        }

        return startStackTrace(classPattern, methodPattern, maxCount, timeoutSeconds, depth, sink);
    }

    private boolean isLegacyAction(String action) {
        return "dump".equals(action) ||
            "monitor".equals(action) ||
            "analyze".equals(action) ||
            "blocked".equals(action) ||
            "deadlock".equals(action) ||
            "hot".equals(action) ||
            "stats".equals(action) ||
            "clear".equals(action);
    }

    private String startStackTrace(String classPattern, String methodPattern,
                                   int maxCount, long timeoutSeconds, int depth,
                                   StreamSink sink) throws Exception {
        if (transformer == null) {
            return "Stack trace mode requires transformer, but transformer is null.";
        }

        // 简化策略：只选择一个已加载的匹配类
        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        Class<?> target = null;
        for (Class<?> c : loaded) {
            if (c != null && WildcardMatcher.matches(c.getName(), classPattern)) {
                target = c;
                break;
            }
        }
        if (target == null) {
            return "No loaded class matches pattern: " + classPattern;
        }

        String stackId = UUID.randomUUID().toString();
        BlockingQueue<StackTraceResult> q = new LinkedBlockingQueue<>(config.getWatchQueueCapacity());

        ClassEnhancer enhancer = new StackEnhancer(target.getName(), methodPattern, null, stackId, depth);
        boolean interceptorRegistered = false;
        boolean enhancerAdded = false;
        try {
            StackInterceptor.register(stackId, q);
            interceptorRegistered = true;

            transformer.addEnhancer(target.getName(), enhancer);
            enhancerAdded = true;

            instrumentation.retransformClasses(target);

            activeSessions.put(stackId, new StackSession(stackId, target, methodPattern, q, enhancer));
        } catch (Exception e) {
            // Rollback partial state best-effort.
            if (enhancerAdded) {
                try {
                    transformer.removeEnhancer(target.getName(), enhancer);
                } catch (Exception ignore) {
                    // ignore
                }
                try {
                    instrumentation.retransformClasses(target);
                } catch (Exception ignore) {
                    // ignore
                }
            }
            if (interceptorRegistered) {
                try {
                    StackInterceptor.unregister(stackId);
                } catch (Exception ignore) {
                    // ignore
                }
            }
            throw e;
        }

        ClientSession clientSession = null;
        String cleanupKey = null;
        try {
            CommandContext ctx = CommandContextHolder.get();
            clientSession = ctx != null ? ctx.getClientSession() : null;
            if (clientSession != null) {
                cleanupKey = "stack:" + stackId;
                clientSession.registerCleanup(cleanupKey, () -> stopSession(stackId));
            }
        } catch (Exception ignore) {
            // ignore
        }

        StringBuilder banner = new StringBuilder();
        banner.append("Started stack trace ").append(target.getName()).append(".").append(methodPattern).append("\n");
        banner.append("Stack ID: ").append(stackId).append("\n");
        banner.append("Max events: ").append(maxCount)
            .append(", Timeout: ").append(timeoutSeconds).append("s")
            .append(", Depth: ").append(depth)
            .append("\n");

        if (sink != null) {
            sink.send(banner.toString().trim());
        }

        StringBuilder out = new StringBuilder();
        int events = 0;
        long startMs = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        try {
            while (events < maxCount) {
                long remaining = timeoutMs - (System.currentTimeMillis() - startMs);
                if (remaining <= 0) {
                    appendOrSend(out, sink, "\nStack timeout reached");
                    break;
                }
                StackTraceResult r = q.poll(Math.min(remaining, 1000), TimeUnit.MILLISECONDS);
                if (r != null) {
                    events++;
                    appendOrSend(out, sink, formatStackTraceResult(r, events));
                }
            }
        } finally {
            stopSession(stackId);
            if (clientSession != null && cleanupKey != null) {
                clientSession.removeCleanup(cleanupKey);
            }
        }

        String summary = "Stack completed. totalEvents=" + events;
        if (sink != null) {
            sink.close(summary);
            return "";
        }
        out.append(summary);
        return out.toString();
    }

    private boolean stopSession(String stackId) {
        StackSession session = activeSessions.remove(stackId);
        if (session == null) {
            StackInterceptor.unregister(stackId);
            return false;
        }
        try {
            transformer.removeEnhancer(session.target.getName(), session.enhancer);
            instrumentation.retransformClasses(session.target);
        } catch (Exception ignored) {
            // best-effort
        } finally {
            StackInterceptor.unregister(stackId);
        }
        return true;
    }

    private String formatStackTraceResult(StackTraceResult r, int idx) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%3d] ", idx));
        sb.append(String.format("%s ", LocalTime.now().toString()));
        sb.append(r.getClassName()).append(".").append(r.getMethodName());
        sb.append(" [").append(r.getThreadName()).append("#").append(r.getThreadId()).append("]");
        sb.append("\n");

        StackTraceElement[] st = r.getStackTrace();
        if (st != null) {
            for (StackTraceElement e : st) {
                if (e == null) {
                    continue;
                }
                sb.append("    at ").append(e.toString()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private void appendOrSend(StringBuilder buf, StreamSink sink, String text) {
        if (sink != null) {
            sink.send(text);
        } else {
            buf.append(text).append("\n");
        }
    }

    private int parseInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private long parseLong(String raw, long def) {
        if (raw == null) {
            return def;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return def;
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
        sb.append(StringUtils.repeat('-', 100)).append("\n");

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
        return "Stack command usage:\n" +
            "  stack <class-pattern> <method-pattern> [options]   - Trace call stacks when method is invoked (lite)\n" +
            "  stack dump [thread-id]                            - Dump stack traces (all threads or specific)\n" +
            "  stack monitor start [interval]                    - Start continuous monitoring (default 1000ms)\n" +
            "  stack monitor stop                                - Stop monitoring\n" +
            "  stack monitor status                              - Show monitoring status\n" +
            "  stack analyze [limit]                             - Analyze collected stack patterns\n" +
            "  stack blocked                                     - Show blocked/waiting threads\n" +
            "  stack deadlock                                    - Check for deadlocks\n" +
            "  stack hot [limit]                                 - Show hottest stack traces\n" +
            "  stack stats                                       - Show stack statistics\n" +
            "  stack clear                                       - Clear collected data\n" +
            "\n" +
            "Stack trace options:\n" +
            "  -n, --count <num>     Max events to capture (default: 10)\n" +
            "  -t, --timeout <sec>   Timeout in seconds (default: 30)\n" +
            "  --depth <frames>      Max stack frames to print (default: 20, max: 200)\n" +
            "  --bg                 Run in background (use jobs tail/stop)\n" +
            "\n" +
            "Examples:\n" +
            "  stack com.example.* doWork -n 5 --depth 30\n" +
            "  stack com.example.* doWork --bg\n" +
            "  stack dump\n" +
            "  stack monitor start 500\n" +
            "  stack analyze 20";
    }

    @Override
    public String getDescription() {
        return "Stack sampling and Arthas-like stack trace (lite)";
    }

    private String getTraceHelp() {
        return "Stack trace (lite) usage:\n" +
            "  stack <class-pattern> <method-pattern> [options]\n\n" +
            "Options:\n" +
            "  -n, --count <num>     Max events to capture (default: 10)\n" +
            "  -t, --timeout <sec>   Timeout in seconds (default: 30)\n" +
            "  --depth <frames>      Max stack frames to print (default: 20, max: 200)\n" +
            "  --bg                 Run in background (use jobs tail/stop)\n" +
            "  -h, --help           Show this help\n\n" +
            "Examples:\n" +
            "  stack com.example.* doWork -n 5 --depth 30\n" +
            "  stack *Service* *method* -t 60\n";
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

    private static String[] removeFlag(String[] args, String flag) {
        if (args == null || args.length == 0 || flag == null || flag.isEmpty()) {
            return args;
        }
        List<String> out = new ArrayList<>();
        for (String a : args) {
            if (a == null) {
                continue;
            }
            if (flag.equals(a)) {
                continue;
            }
            out.add(a);
        }
        return out.toArray(new String[0]);
    }

    private static final class StackSession {
        private final String stackId;
        private final Class<?> target;
        private final String methodPattern;
        private final BlockingQueue<StackTraceResult> queue;
        private final ClassEnhancer enhancer;

        private StackSession(String stackId, Class<?> target, String methodPattern,
                             BlockingQueue<StackTraceResult> queue, ClassEnhancer enhancer) {
            this.stackId = stackId;
            this.target = target;
            this.methodPattern = methodPattern;
            this.queue = queue;
            this.enhancer = enhancer;
        }
    }
}
