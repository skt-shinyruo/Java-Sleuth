package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.command.StreamCommand;
import com.javasleuth.command.StreamSink;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.enhancement.ClassEnhancer;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.enhancement.WatchEnhancer;
import com.javasleuth.monitor.WatchInterceptor;
import com.javasleuth.data.WatchResult;
import com.javasleuth.command.JobManager;
import com.javasleuth.util.SleuthConditionEvaluator;
import com.javasleuth.util.SleuthValueFormatter;
import com.javasleuth.util.WildcardMatcher;
import java.lang.instrument.Instrumentation;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class WatchCommand implements StreamCommand {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ProductionConfig config;
    private final ConcurrentHashMap<String, WatchSession> activeSessions = new ConcurrentHashMap<>();

    public WatchCommand(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = ProductionConfig.getInstance();
    }

    @Override
    public String execute(String[] args) throws Exception {
        return runWatch(args, null);
    }

    @Override
    public void executeStream(String[] args, StreamSink sink) throws Exception {
        runWatch(args, sink);
    }

    private String runWatch(String[] args, StreamSink sink) throws Exception {
        if (args.length < 3) {
            String help = getHelp();
            if (sink != null) {
                sink.error(help);
                return "";
            }
            return help;
        }

        // Background mode
        boolean background = false;

        String classPattern = args[1];
        String methodPattern = args[2];

        // Parse options
        boolean captureParams = true;
        boolean captureReturn = true;
        boolean captureException = true;
        int maxCount = 100;
        long timeoutSeconds = 30;
        String exprRaw = null;
        List<String> rawConditions = new ArrayList<>();

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "-n":
                case "--count":
                    if (i + 1 < args.length) {
                        maxCount = Integer.parseInt(args[++i]);
                    }
                    break;
                case "-t":
                case "--timeout":
                    if (i + 1 < args.length) {
                        timeoutSeconds = Long.parseLong(args[++i]);
                    }
                    break;
                case "--expr":
                    if (i + 1 < args.length) {
                        exprRaw = args[++i];
                    }
                    break;
                case "--condition":
                    if (i + 1 < args.length) {
                        rawConditions.add(args[++i]);
                    }
                    break;
                case "--bg":
                    background = true;
                    break;
                case "--no-params":
                    captureParams = false;
                    break;
                case "--no-return":
                    captureReturn = false;
                    break;
                case "--no-exception":
                    captureException = false;
                    break;
                case "-h":
                case "--help":
                    return getHelp();
            }
        }

        if (background) {
            String[] jobArgs = removeFlag(args, "--bg");
            String commandLine = String.join(" ", jobArgs);
            String jobId = JobManager.getInstance().submitStreamJob(
                "watch",
                commandLine,
                jobSink -> runWatch(jobArgs, jobSink)
            );
            String msg = "Started watch in background. Job ID: " + jobId + " (use: jobs tail " + jobId + ")";
            if (sink != null) {
                sink.send(msg);
                sink.close("job started");
                return "";
            }
            return msg;
        }

        List<String> expr = parseExpr(exprRaw);
        List<SleuthConditionEvaluator.Condition> conditions = SleuthConditionEvaluator.parseConditions(rawConditions);

        return startWatching(classPattern, methodPattern, captureParams, captureReturn,
            captureException, maxCount, timeoutSeconds, expr, conditions, sink);
    }

    private String startWatching(String classPattern, String methodPattern,
                               boolean captureParams, boolean captureReturn, boolean captureException,
                               int maxCount, long timeoutSeconds,
                               List<String> expr,
                               List<SleuthConditionEvaluator.Condition> conditions,
                               StreamSink sink) throws Exception {

        // Find matching classes
        Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();
        String targetClassName = null;

        for (Class<?> clazz : loadedClasses) {
            if (matchesPattern(clazz.getName(), classPattern)) {
                targetClassName = clazz.getName();
                break;
            }
        }

        if (targetClassName == null) {
            return "No loaded class matches pattern: " + classPattern;
        }

        String watchId = UUID.randomUUID().toString();
        BlockingQueue<WatchResult> resultQueue = new LinkedBlockingQueue<>(config.getWatchQueueCapacity());

        // Use the actual loaded Class<?> instance to preserve classloader correctness.
        Class<?> targetClass = null;
        for (Class<?> c : loadedClasses) {
            if (c != null && targetClassName.equals(c.getName())) {
                targetClass = c;
                break;
            }
        }
        if (targetClass == null) {
            return "Target class not found in loaded classes: " + targetClassName;
        }

        WatchEnhancer enhancer = new WatchEnhancer(targetClassName, methodPattern, null,
            captureParams, captureReturn, captureException, watchId);
        boolean interceptorRegistered = false;
        boolean enhancerAdded = false;
        try {
            // Register the watch
            WatchInterceptor.registerWatch(watchId, resultQueue);
            interceptorRegistered = true;

            // Create and register enhancer
            transformer.addEnhancer(targetClassName, enhancer);
            enhancerAdded = true;

            // Retransform the class
            instrumentation.retransformClasses(targetClass);

            // Create watch session
            WatchSession session = new WatchSession(watchId, targetClassName, methodPattern, resultQueue, enhancer);
            activeSessions.put(watchId, session);
        } catch (Exception e) {
            // Rollback partial state best-effort.
            if (enhancerAdded) {
                try {
                    transformer.removeEnhancer(targetClassName, enhancer);
                } catch (Exception ignore) {
                    // ignore
                }
                try {
                    instrumentation.retransformClasses(targetClass);
                } catch (Exception ignore) {
                    // ignore
                }
            }
            if (interceptorRegistered) {
                try {
                    WatchInterceptor.unregisterWatch(watchId);
                } catch (Exception ignore) {
                    // ignore
                }
            }
            throw e;
        }

        StringBuilder result = new StringBuilder();
        result.append("Started watching ").append(targetClassName).append(".").append(methodPattern).append("\n");
        result.append("Watch ID: ").append(watchId).append("\n");
        result.append("Capturing: ");
        if (captureParams) result.append("params ");
        if (captureReturn) result.append("return ");
        if (captureException) result.append("exception ");
        result.append("\n");
        result.append("Max events: ").append(maxCount).append(", Timeout: ").append(timeoutSeconds).append("s\n");
        result.append("Press Ctrl+C to stop watching\n\n");

        if (sink != null) {
            sink.send(result.toString().trim());
            result.setLength(0);
        }

        // Collect results
        int eventCount = 0;
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        try {
            while (eventCount < maxCount) {
                long remainingTime = timeoutMs - (System.currentTimeMillis() - startTime);
                if (remainingTime <= 0) {
                    appendOrSend(result, sink, "\nWatch timeout reached");
                    break;
                }

                WatchResult watchResult = resultQueue.poll(Math.min(remainingTime, 1000), TimeUnit.MILLISECONDS);
                if (watchResult != null) {
                    if (!SleuthConditionEvaluator.matchesWatch(watchResult, conditions)) {
                        continue;
                    }
                    eventCount++;
                    appendOrSend(result, sink, formatWatchResult(watchResult, eventCount, expr));
                } else {
                    // Check if we should continue (no results for 1 second)
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        appendOrSend(result, sink, "\nWatch timeout reached");
                        break;
                    }
                }
            }

            if (eventCount >= maxCount) {
                appendOrSend(result, sink, "\nMaximum event count reached");
            }

        } finally {
            // Cleanup
            stopWatch(watchId);
        }

        String summary = "Watch completed. Total events: " + eventCount;
        if (sink != null) {
            sink.close(summary);
            return "";
        }
        result.append(summary);
        return result.toString();
    }

    private void stopWatch(String watchId) {
        WatchSession session = activeSessions.remove(watchId);
        if (session != null) {
            try {
                // Remove enhancer
                transformer.removeEnhancer(session.getClassName(), session.getEnhancer());

                // Retransform class back to original (best-effort using loaded instance)
                Class<?> targetClass = findLoadedClass(session.getClassName());
                if (targetClass != null) {
                    instrumentation.retransformClasses(targetClass);
                }

                // Unregister watch
                WatchInterceptor.unregisterWatch(watchId);

            } catch (Exception e) {
                System.err.println("Error stopping watch: " + e.getMessage());
            }
        }
    }

    private String formatWatchResult(WatchResult result, int eventNumber) {
        return formatWatchResult(result, eventNumber, null);
    }

    private String formatWatchResult(WatchResult result, int eventNumber, List<String> expr) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%3d] ", eventNumber));
        sb.append(String.format("%s ", LocalTime.now().toString()));

        if (expr == null || expr.isEmpty()) {
            sb.append(result.toString());
            return sb.toString();
        }

        Set<String> keys = new HashSet<>();
        for (String e : expr) {
            if (e != null && !e.trim().isEmpty()) {
                keys.add(e.trim().toLowerCase(Locale.ROOT));
            }
        }

        SleuthValueFormatter.Options opt = new SleuthValueFormatter.Options()
            .withMaxDepth(2)
            .withMaxStringLength(200)
            .withMaxCollectionItems(20)
            .withMaxMapEntries(20);

        sb.append("[")
            .append(result.getEventType() == null ? "EVENT" : result.getEventType().name())
            .append("] ");

        if (keys.contains("class") || keys.contains("method") || keys.contains("signature")) {
            sb.append(result.getClassName()).append(".").append(result.getMethodName());
        } else {
            sb.append(result.getClassName()).append(".").append(result.getMethodName());
        }

        if (keys.contains("params") && result.getParameters() != null && result.getEventType() == WatchResult.EventType.METHOD_ENTRY) {
            sb.append(" params=").append(SleuthValueFormatter.format(result.getParameters(), opt));
        }
        if (keys.contains("return") && result.getEventType() == WatchResult.EventType.METHOD_EXIT) {
            sb.append(" return=").append(SleuthValueFormatter.format(result.getReturnValue(), opt));
        }
        if ((keys.contains("throw") || keys.contains("exception")) && result.getEventType() == WatchResult.EventType.METHOD_EXCEPTION) {
            sb.append(" throw=").append(SleuthValueFormatter.format(result.getException(), opt));
        }
        if (keys.contains("cost") && result.getEventType() != WatchResult.EventType.METHOD_ENTRY) {
            sb.append(" cost=").append(result.formatDuration());
        }
        if (keys.contains("thread")) {
            sb.append(" [").append(result.getThreadName()).append("#").append(result.getThreadId()).append("]");
        }
        return sb.toString();
    }

    private void appendOrSend(StringBuilder result, StreamSink sink, String text) {
        if (sink != null) {
            sink.send(text);
        } else {
            result.append(text).append("\n");
        }
    }

    private boolean matchesPattern(String className, String pattern) {
        return WildcardMatcher.matches(className, pattern);
    }

    private Class<?> findLoadedClass(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        try {
            for (Class<?> c : instrumentation.getAllLoadedClasses()) {
                if (c != null && className.equals(c.getName())) {
                    return c;
                }
            }
        } catch (Exception ignore) {
            // best-effort
        }
        return null;
    }

    private String getHelp() {
        return "Watch command usage:\n" +
               "  watch <class-pattern> <method-pattern> [options]\n\n" +
               "Options:\n" +
               "  -n, --count <num>     Maximum number of events to capture (default: 100)\n" +
               "  -t, --timeout <sec>   Timeout in seconds (default: 30)\n" +
               "  --expr <fields>       Output fields (comma-separated), e.g. params,return,throw,cost,thread\n" +
               "  --condition <c>       Filter condition (lhs:op:rhs), can repeat; e.g. cost:gt:1000000\n" +
               "  --bg                 Run in background (use jobs tail/stop)\n" +
               "  --no-params          Don't capture method parameters\n" +
               "  --no-return          Don't capture return values\n" +
               "  --no-exception       Don't capture exceptions\n" +
               "  -h, --help           Show this help\n\n" +
               "Examples:\n" +
               "  watch com.example.* execute*\n" +
               "  watch *Service* *method* -n 50 -t 60 --condition cost:gt:1000000\n" +
               "  watch MyClass doWork --no-params\n";
    }

    private static List<String> parseExpr(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String[] parts = raw.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String v = p.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
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

    @Override
    public String getDescription() {
        return "Watch method execution with parameters, return values, and timing";
    }

    private static class WatchSession {
        private final String watchId;
        private final String className;
        private final String methodPattern;
        private final BlockingQueue<WatchResult> resultQueue;
        private final ClassEnhancer enhancer;

        public WatchSession(String watchId, String className, String methodPattern, BlockingQueue<WatchResult> resultQueue, ClassEnhancer enhancer) {
            this.watchId = watchId;
            this.className = className;
            this.methodPattern = methodPattern;
            this.resultQueue = resultQueue;
            this.enhancer = enhancer;
        }

        public String getWatchId() { return watchId; }
        public String getClassName() { return className; }
        public String getMethodPattern() { return methodPattern; }
        public BlockingQueue<WatchResult> getResultQueue() { return resultQueue; }
        public ClassEnhancer getEnhancer() { return enhancer; }
    }
}
