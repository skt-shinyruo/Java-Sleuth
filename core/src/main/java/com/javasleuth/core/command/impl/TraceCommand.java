package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.StreamCommand;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.core.enhancement.ClassEnhancer;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.TraceEnhancer;
import com.javasleuth.bootstrap.monitor.TraceAggregator;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.data.TraceResult;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.foundation.util.LoadedClassResolver;
import com.javasleuth.core.command.util.SleuthConditionEvaluator;
import com.javasleuth.foundation.util.SleuthLogger;
import com.javasleuth.foundation.util.WildcardMatcher;
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

public class TraceCommand implements StreamCommand {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConfigView config;
    private final JobManager jobManager;
    private final ConcurrentHashMap<String, TraceSession> activeSessions = new ConcurrentHashMap<>();

    public TraceCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = config;
        if (jobManager == null) {
            throw new IllegalArgumentException("jobManager");
        }
        this.jobManager = jobManager;
    }

    @Override
    public String execute(String[] args) throws Exception {
        return runTrace(args, null);
    }

    @Override
    public void executeStream(String[] args, StreamSink sink) throws Exception {
        runTrace(args, sink);
    }

    private String runTrace(String[] args, StreamSink sink) throws Exception {
        if (args.length < 3) {
            String help = getHelp();
            if (sink != null) {
                sink.error(help);
                return "";
            }
            return help;
        }

        boolean background = false;
        Integer loaderId = null;
        boolean allowFirstWhenAmbiguous = false;
        String classPattern = args[1];
        String methodPattern = args[2];

        // Parse options
        int maxDepth = 10;
        int maxCount = 20;
        long timeoutSeconds = 30;
        String exprRaw = null;
        List<String> rawConditions = new ArrayList<>();
        Double sampleRateOverride = null;

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "-d":
                case "--depth":
                    if (i + 1 < args.length) {
                        maxDepth = Integer.parseInt(args[++i]);
                    }
                    break;
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
                case "--sample":
                case "--sample-rate":
                    if (i + 1 < args.length) {
                        sampleRateOverride = parseSampleRate(args[++i]);
                    }
                    break;
                case "--bg":
                    background = true;
                    break;
                case "--loader":
                case "--loader-id":
                case "--loader-hash":
                    if (i + 1 < args.length) {
                        String raw = args[++i];
                        Integer parsed = LoadedClassResolver.parseLoaderId(raw);
                        if (parsed == null) {
                            String msg = "Invalid --loader value: " + raw +
                                " (expected: bootstrap/null/0x1234/1234)";
                            if (sink != null) {
                                sink.error(msg);
                                return "";
                            }
                            return msg;
                        }
                        loaderId = parsed;
                    }
                    break;
                case "--first":
                case "--unsafe-first":
                    allowFirstWhenAmbiguous = true;
                    break;
                case "-h":
                case "--help":
                    return getHelp();
            }
        }

        if (background) {
            String[] jobArgs = removeFlag(args, "--bg");
            String commandLine = String.join(" ", jobArgs);
            String jobId = jobManager.submitStreamJob(
                "trace",
                commandLine,
                jobSink -> runTrace(jobArgs, jobSink)
            );
            String msg = "Started trace in background. Job ID: " + jobId + " (use: jobs tail " + jobId + ")";
            if (sink != null) {
                sink.send(msg);
                sink.close("job started");
                return "";
            }
            return msg;
        }

        List<String> expr = parseExpr(exprRaw);
        List<SleuthConditionEvaluator.Condition> conditions = SleuthConditionEvaluator.parseConditions(rawConditions);

        return startTracing(classPattern, methodPattern, maxDepth, maxCount, timeoutSeconds, loaderId, allowFirstWhenAmbiguous,
            expr, conditions, sampleRateOverride, sink);
    }

    private String startTracing(String classPattern, String methodPattern,
                              int maxDepth, int maxCount, long timeoutSeconds,
                              Integer loaderId,
                              boolean allowFirstWhenAmbiguous,
                              List<String> expr,
                              List<SleuthConditionEvaluator.Condition> conditions,
                              Double sampleRateOverride,
                              StreamSink sink) throws Exception {

        // Find matching class (multi-ClassLoader safe)
        LoadedClassResolver.Candidate resolved;
        try {
            resolved = LoadedClassResolver.resolveSingle(instrumentation, classPattern, loaderId, true, 200, allowFirstWhenAmbiguous);
        } catch (LoadedClassResolver.ResolutionException e) {
            String msg = e.getMessage() +
                "\nCandidates:\n" + LoadedClassResolver.formatCandidates(e.getCandidates(), 10) +
                "\nHint: use --loader <loaderId> (e.g. --loader 0x1234 or --loader bootstrap)";
            if (sink != null) {
                sink.error(msg);
                return "";
            }
            return msg;
        }
        String targetClassName = resolved.getClassName();
        Class<?> targetClass = resolved.getClazz();

        String traceId = UUID.randomUUID().toString();
        BlockingQueue<TraceResult> resultQueue = new LinkedBlockingQueue<>(config.getInt("monitoring.trace.queue.capacity", 2000));
        if (targetClass == null) {
            String msg = "Target class not found in loaded classes: " + targetClassName;
            if (sink != null) {
                sink.error(msg);
                return "";
            }
            return msg;
        }

        TraceEnhancer enhancer = new TraceEnhancer(targetClassName, methodPattern, null, traceId);
        boolean interceptorRegistered = false;
        boolean enhancerAdded = false;
        try {
            // Register the trace
            TraceInterceptor.registerTrace(traceId, resultQueue, sampleRateOverride);
            interceptorRegistered = true;

            // Create and register enhancer
            transformer.addEnhancer(targetClass, enhancer);
            enhancerAdded = true;

            // Retransform the class
            instrumentation.retransformClasses(targetClass);

            // Create trace session
            TraceSession session = new TraceSession(traceId, targetClass, targetClassName, methodPattern, resultQueue, enhancer);
            activeSessions.put(traceId, session);
        } catch (Exception e) {
            // Rollback partial state best-effort.
            if (enhancerAdded) {
                try {
                    transformer.removeEnhancer(targetClass, enhancer);
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
                    TraceInterceptor.unregisterTrace(traceId);
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
                cleanupKey = "trace:" + traceId;
                clientSession.registerCleanup(cleanupKey, () -> stopTrace(traceId));
            }
        } catch (Exception ignore) {
            // ignore
        }

        StringBuilder result = new StringBuilder();
        result.append("Started tracing ").append(targetClassName)
            .append(" (loaderId=").append(LoadedClassResolver.formatLoaderId(resolved.getLoaderId())).append(")")
            .append(".").append(methodPattern).append("\n");
        result.append("Trace ID: ").append(traceId).append("\n");
        result.append("Max depth: ").append(maxDepth).append(", Max invocations: ").append(maxCount);
        result.append(", Timeout: ").append(timeoutSeconds).append("s\n");
        if (sampleRateOverride != null) {
            result.append("Sample rate override: ").append(sampleRateOverride).append("\n");
        }
        result.append("Press Ctrl+C to stop tracing\n\n");

        if (sink != null) {
            sink.send(result.toString().trim());
            result.setLength(0);
        }

        TraceAggregator aggregator = new TraceAggregator(
            new TraceAggregator.Options().withMaxDepth(maxDepth).withMaxNodes(4000)
        );

        // Collect results (per-invocation)
        int invocationCount = 0;
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        try {
            while (invocationCount < maxCount) {
                long remainingTime = timeoutMs - (System.currentTimeMillis() - startTime);
                if (remainingTime <= 0) {
                    appendOrSend(result, sink, "\nTrace timeout reached");
                    break;
                }

                TraceResult traceResult = resultQueue.poll(Math.min(remainingTime, 1000), TimeUnit.MILLISECONDS);
                if (traceResult != null) {
                    aggregator.accept(traceResult);
                    for (TraceAggregator.Invocation inv : aggregator.drainCompleted()) {
                        if (!SleuthConditionEvaluator.matchesTraceInvocation(inv, conditions)) {
                            continue;
                        }
                        invocationCount++;
                        appendOrSend(result, sink, formatTraceInvocation(inv, invocationCount, expr, maxDepth));
                        if (invocationCount >= maxCount) {
                            break;
                        }
                    }
                } else {
                    // Check if we should continue (no results for 1 second)
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        appendOrSend(result, sink, "\nTrace timeout reached");
                        break;
                    }
                }
            }

            if (invocationCount >= maxCount) {
                appendOrSend(result, sink, "\nMaximum invocation count reached");
            }

        } finally {
            // Cleanup
            stopTrace(traceId);
            if (clientSession != null && cleanupKey != null) {
                clientSession.removeCleanup(cleanupKey);
            }
        }

        String summary = "Trace completed. Total invocations: " + invocationCount;
        if (sink != null) {
            sink.close(summary);
            return "";
        }
        result.append(summary);
        return result.toString();
    }

    private void stopTrace(String traceId) {
        TraceSession session = activeSessions.remove(traceId);
        if (session != null) {
            try {
                // Remove enhancer
                if (session.getTargetClass() != null) {
                    transformer.removeEnhancer(session.getTargetClass(), session.getEnhancer());
                }

                // Retransform class back to original (best-effort using loaded instance)
                if (session.getTargetClass() != null) {
                    instrumentation.retransformClasses(session.getTargetClass());
                }

                // Unregister trace
                TraceInterceptor.unregisterTrace(traceId);

            } catch (Exception e) {
                SleuthLogger.warn("Error stopping trace: " + e.getMessage(), e);
            }
        }
    }

    private String formatTraceResult(TraceResult result, int eventNumber) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%3d] ", eventNumber));
        sb.append(String.format("%s ", LocalTime.now().toString()));
        sb.append(result.toString());
        return sb.toString();
    }

    private String formatTraceInvocation(TraceAggregator.Invocation inv, int number, List<String> expr, int maxDepth) {
        Set<String> keys = new HashSet<>();
        if (expr != null) {
            for (String e : expr) {
                if (e != null && !e.trim().isEmpty()) {
                    keys.add(e.trim().toLowerCase(Locale.ROOT));
                }
            }
        }

        boolean includeTree = keys.isEmpty() || keys.contains("tree") || keys.contains("trace");
        boolean includeThread = keys.contains("thread");
        boolean includeCost = keys.isEmpty() || keys.contains("cost");

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%3d] ", number));
        sb.append(String.format("%s ", LocalTime.now().toString()));

        TraceAggregator.Node root = inv.getRoot();
        sb.append(root.getClassName()).append(".").append(root.getMethodName()).append("()");
        if (includeCost) {
            sb.append(" cost=").append(formatNanos(root.getDuration()));
        }
        if (root.isException()) {
            sb.append(" [EXCEPTION]");
        }
        if (includeThread) {
            sb.append(" [").append(inv.getThreadName()).append("#").append(inv.getThreadId()).append("]");
        }

        if (!includeTree) {
            return sb.toString();
        }

        sb.append("\n");
        int renderDepth = maxDepth > 0 ? maxDepth : 10;
        renderNode(sb, root, "", true, 0, renderDepth);
        return sb.toString().trim();
    }

    private void renderNode(StringBuilder sb, TraceAggregator.Node node, String indent, boolean last, int depth, int maxDepth) {
        if (node == null) {
            return;
        }
        String head = last ? "└─ " : "├─ ";
        sb.append(indent).append(head)
            .append(node.getClassName()).append(".").append(node.getMethodName()).append("()");
        sb.append(" ").append(formatNanos(node.getDuration()));
        if (node.isException()) {
            sb.append(" [EXCEPTION]");
        }
        sb.append("\n");

        if (depth >= maxDepth) {
            return;
        }

        String childIndent = indent + (last ? "   " : "│  ");
        List<TraceAggregator.Item> children = node.getChildren();
        for (int i = 0; i < children.size(); i++) {
            TraceAggregator.Item child = children.get(i);
            boolean childLast = i == children.size() - 1;
            if (child instanceof TraceAggregator.Node) {
                renderNode(sb, (TraceAggregator.Node) child, childIndent, childLast, depth + 1, maxDepth);
            } else if (child instanceof TraceAggregator.SubCall) {
                TraceAggregator.SubCall sc = (TraceAggregator.SubCall) child;
                sb.append(childIndent).append(childLast ? "└─ " : "├─ ")
                    .append(sc.getClassName()).append(".").append(sc.getMethodName()).append("()");
                if (sc.getDuration() > 0) {
                    sb.append(" ").append(formatNanos(sc.getDuration()));
                }
                sb.append("\n");
            }
        }
    }

    private String formatNanos(long nanos) {
        long d = Math.max(0, nanos);
        if (d < 1_000) {
            return d + "ns";
        } else if (d < 1_000_000) {
            return String.format(Locale.ROOT, "%.2fμs", d / 1_000.0);
        } else if (d < 1_000_000_000) {
            return String.format(Locale.ROOT, "%.2fms", d / 1_000_000.0);
        } else {
            return String.format(Locale.ROOT, "%.2fs", d / 1_000_000_000.0);
        }
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
        return "Trace command usage:\n" +
               "  trace <class-pattern> <method-pattern> [options]\n\n" +
               "Options:\n" +
               "  -d, --depth <num>     Maximum trace depth (default: 10)\n" +
               "  -n, --count <num>     Maximum number of invocations to capture (default: 20)\n" +
               "  -t, --timeout <sec>   Timeout in seconds (default: 30)\n" +
               "  --loader <id>         Select target ClassLoader when multiple loaded classes match\n" +
               "  --first               Use first matched class when ambiguous (unsafe)\n" +
               "  --expr <fields>       Output fields (comma-separated), e.g. tree,cost,thread\n" +
               "  --condition <c>       Filter condition (lhs:op:rhs), can repeat; e.g. cost:gt:1000000\n" +
               "  --sample <rate>       Override sample rate for this trace (0.0..1.0)\n" +
               "  --bg                 Run in background (use jobs tail/stop)\n" +
               "  -h, --help            Show this help\n\n" +
               "Examples:\n" +
               "  trace com.example.* execute*\n" +
               "  trace *Service* *method* -d 5 -n 50\n" +
               "  trace MyClass doWork -t 60\n" +
               "  trace MyClass doWork --sample 1.0\n";
    }

    private static Double parseSampleRate(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            if (v < 0) {
                v = 0;
            } else if (v > 1.0) {
                v = 1.0;
            }
            return v;
        } catch (Exception ignore) {
            return null;
        }
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
        return "Trace method execution call chains with timing";
    }

    private static class TraceSession {
        private final String traceId;
        private final Class<?> targetClass;
        private final String className;
        private final String methodPattern;
        private final BlockingQueue<TraceResult> resultQueue;
        private final ClassEnhancer enhancer;

        public TraceSession(String traceId, Class<?> targetClass, String className, String methodPattern,
                            BlockingQueue<TraceResult> resultQueue, ClassEnhancer enhancer) {
            this.traceId = traceId;
            this.targetClass = targetClass;
            this.className = className;
            this.methodPattern = methodPattern;
            this.resultQueue = resultQueue;
            this.enhancer = enhancer;
        }

        public String getTraceId() { return traceId; }
        public Class<?> getTargetClass() { return targetClass; }
        public String getClassName() { return className; }
        public String getMethodPattern() { return methodPattern; }
        public BlockingQueue<TraceResult> getResultQueue() { return resultQueue; }
        public ClassEnhancer getEnhancer() { return enhancer; }
    }
}
