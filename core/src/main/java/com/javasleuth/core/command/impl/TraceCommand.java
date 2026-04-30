package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.CancellationToken;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.StreamCommand;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.CommandSpecParser;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.agent.runtime.BootstrapBridge;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.MonitoringConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.core.enhancement.ClassEnhancer;
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.TraceEnhancer;
import com.javasleuth.bootstrap.monitor.TraceAggregator;
import com.javasleuth.bootstrap.data.TraceResult;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.spy.listener.TraceAdviceListener;
import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.util.LoadedClassResolver;
import com.javasleuth.core.command.util.SleuthConditionEvaluator;
import com.javasleuth.foundation.util.SleuthLogger;
import com.javasleuth.foundation.util.WildcardMatcher;
import java.lang.instrument.Instrumentation;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class TraceCommand implements StreamCommand, SpecBackedCommand {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConfigView config;
    private final JobManager jobManager;
    private final SleuthSpyDispatcher spyDispatcher;
    private final EnhancementSessionRegistry sessionRegistry;
    private final ConcurrentHashMap<String, TraceSession> activeSessions = new ConcurrentHashMap<>();

    public TraceCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager
    ) {
        this(instrumentation, transformer, config, jobManager, null);
    }

    public TraceCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager,
        SleuthSpyDispatcher spyDispatcher
    ) {
        this(instrumentation, transformer, config, jobManager, spyDispatcher, null);
    }

    public TraceCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager,
        SleuthSpyDispatcher spyDispatcher,
        EnhancementSessionRegistry sessionRegistry
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = config;
        if (jobManager == null) {
            throw new IllegalArgumentException("jobManager");
        }
        this.jobManager = jobManager;
        this.spyDispatcher = spyDispatcher;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public String execute(String[] args) throws Exception {
        return runTrace(args, null);
    }

    @Override
    public void executeStream(String[] args, StreamSink sink) throws Exception {
        runTrace(args, sink);
    }

    public static CommandSpec spec() {
        return CommandSpec.builder("trace")
            .description("Trace method execution call chains with timing")
            .usage("trace <class-pattern> <method-pattern> [options]")
            .meta(instrumentationStreamMeta())
            .argument(ArgumentSpec.required("class-pattern"))
            .argument(ArgumentSpec.required("method-pattern"))
            .option(OptionSpec.integer("depth").alias("-d").alias("--depth").defaultValue(10).range(1, 1000).build())
            .option(OptionSpec.integer("count").alias("-n").alias("--count").defaultValue(20).range(1, 100000).build())
            .option(OptionSpec.longNumber("timeout").alias("-t").alias("--timeout").defaultValue(30L).range(1, 86400).build())
            .option(OptionSpec.string("loader").alias("--loader").alias("--loader-id").alias("--loader-hash").build())
            .option(OptionSpec.flag("first").alias("--first").alias("--unsafe-first").build())
            .option(OptionSpec.string("expr").alias("--expr").build())
            .option(OptionSpec.string("condition").alias("--condition").repeatable(true).build())
            .option(OptionSpec.flag("bg").alias("--bg").build())
            .hiddenOption(OptionSpec.string("sample").alias("--sample").build())
            .hiddenOption(OptionSpec.string("sample-rate").alias("--sample-rate").build())
            .example("trace com.example.* execute*")
            .example("trace *Service* *method* -d 5 -n 50")
            .example("trace MyClass doWork -t 60")
            .build();
    }

    @Override
    public CommandSpec getSpec() {
        return spec();
    }

    private String runTrace(String[] args, StreamSink sink) throws Exception {
        if (args == null || args.length < 3) {
            String help = CommandHelpRenderer.render(spec());
            if (sink != null) {
                sink.error(help);
                return "";
            }
            return help;
        }
        ParsedCommand parsed = parsedOrFallback(args);
        if (parsed.isHelpRequested()) {
            String help = CommandHelpRenderer.render(spec());
            if (sink != null) {
                sink.send(help);
                return "";
            }
            return help;
        }

        if (parsed.stringOption("sample") != null || parsed.stringOption("sample-rate") != null) {
            String sampleRemovedMsg = "ERROR: --sample/--sample-rate 已移除 (removed). "
                + "Trace sampling is always 1.0 (collect all).";
            if (sink != null) {
                sink.error(sampleRemovedMsg);
                return "";
            }
            return sampleRemovedMsg;
        }

        String classPattern = parsed.argument("class-pattern");
        String methodPattern = parsed.argument("method-pattern");
        int maxDepth = parsed.intOption("depth");
        int maxCount = parsed.intOption("count");
        long timeoutSeconds = parsed.longOption("timeout");
        Integer loaderId = null;
        String loaderRaw = parsed.stringOption("loader");
        if (loaderRaw != null) {
            loaderId = LoadedClassResolver.parseLoaderId(loaderRaw);
            if (loaderId == null) {
                String msg = "Invalid --loader value: " + loaderRaw + " (expected: bootstrap/null/0x1234/1234)";
                if (sink != null) {
                    sink.error(msg);
                    return "";
                }
                return msg;
            }
        }
        boolean allowFirstWhenAmbiguous = Boolean.TRUE.equals(parsed.booleanOption("first"));

        if (Boolean.TRUE.equals(parsed.booleanOption("bg"))) {
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

        List<String> expr = parseExpr(parsed.stringOption("expr"));
        List<SleuthConditionEvaluator.Condition> conditions = SleuthConditionEvaluator.parseConditions(parsed.stringOptionValues("condition"));

        return startTracing(classPattern, methodPattern, maxDepth, maxCount, timeoutSeconds, loaderId, allowFirstWhenAmbiguous,
            expr, conditions, sink);
    }

    private ParsedCommand parsedOrFallback(String[] args) {
        CommandContext ctx = CommandContextHolder.get();
        ParsedCommand parsed = ctx != null ? ctx.getParsedCommand() : null;
        if (parsed != null && Boolean.TRUE.equals(parsed.booleanOption("bg")) && !hasFlag(args, "--bg")) {
            return CommandSpecParser.parse(spec(), args);
        }
        return parsed != null ? parsed : CommandSpecParser.parse(spec(), args);
    }

    private static CommandMeta instrumentationStreamMeta() {
        return CommandMeta.operator(false, true)
            .requiresBootstrap(BootstrapBridge.SPY_API)
            .withCapability(CommandCapability.LONG_RUNNING);
    }

    private String startTracing(String classPattern, String methodPattern,
                              int maxDepth, int maxCount, long timeoutSeconds,
                              Integer loaderId,
                              boolean allowFirstWhenAmbiguous,
                              List<String> expr,
                              List<SleuthConditionEvaluator.Condition> conditions,
                              StreamSink sink) throws Exception {
        if (!SleuthSpyDispatcher.isInstalled(spyDispatcher)) {
            return SleuthSpyDispatcher.unavailableMessage("trace");
        }

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
        MonitoringConfig monitoring = SleuthConfigParser.parse(configSnapshot()).monitoring();
        BlockingQueue<TraceResult> resultQueue = new LinkedBlockingQueue<>(monitoring.getTraceQueueCapacity());
        if (targetClass == null) {
            String msg = "Target class not found in loaded classes: " + targetClassName;
            if (sink != null) {
                sink.error(msg);
                return "";
            }
            return msg;
        }

        TraceEnhancer enhancer = new TraceEnhancer(targetClassName, methodPattern, null, traceId);
        boolean enhancerAdded = false;
        try {
            boolean dropOnFull = monitoring.isTraceDropOnFull();
            spyDispatcher.register(
                traceId,
                SleuthSpyDispatcher.ListenerKind.TRACE,
                new TraceAdviceListener(traceId, resultQueue, dropOnFull)
            );

            // Create and register enhancer
            transformer.addEnhancer(targetClass, enhancer);
            enhancerAdded = true;

            // Retransform the class
            instrumentation.retransformClasses(targetClass);

            // Create trace session
            TraceSession session = new TraceSession(traceId, targetClass, targetClassName, methodPattern, resultQueue, enhancer);
            activeSessions.put(traceId, session);
            registerEnhancementSession(
                traceId,
                classPattern,
                methodPattern,
                targetClassName,
                resolved.getLoaderId(),
                maxDepth,
                maxCount,
                timeoutSeconds
            );
        } catch (Exception e) {
            // Rollback partial state best-effort.
            activeSessions.remove(traceId);
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
            try {
                spyDispatcher.unregister(traceId);
            } catch (Exception ignore) {
                // ignore
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
                clientSession.registerCleanup(cleanupKey, () -> closeTraceSession(traceId, "client_cleanup"));
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
        CancellationToken token = currentCancellationToken();

        try {
            while (invocationCount < maxCount && !token.isCancelled()) {
                long remainingTime = timeoutMs - (System.currentTimeMillis() - startTime);
                if (remainingTime <= 0) {
                    appendOrSend(result, sink, "\nTrace timeout reached");
                    break;
                }

                TraceResult traceResult;
                try {
                    traceResult = resultQueue.poll(Math.min(remainingTime, 1000), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (token.isCancelled()) {
                        break;
                    }
                    Thread.currentThread().interrupt();
                    throw e;
                }
                if (token.isCancelled()) {
                    break;
                }
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
            closeTraceSession(traceId, "completed");
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

    private ConfigView configSnapshot() {
        return config instanceof ProductionConfig ? ((ProductionConfig) config).snapshot() : config;
    }

    private void closeTraceSession(String traceId, String reason) {
        if (sessionRegistry != null && sessionRegistry.close(traceId, reason)) {
            return;
        }
        stopTraceInternal(traceId);
    }

    private void stopTraceInternal(String traceId) {
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
                try {
                    spyDispatcher.unregister(traceId);
                } catch (Exception ignore) {
                    // ignore
                }

            } catch (Exception e) {
                SleuthLogger.warn("Error stopping trace: " + e.getMessage(), e);
            }
        }
    }

    private void registerEnhancementSession(String traceId,
                                            String classPattern,
                                            String methodPattern,
                                            String targetClassName,
                                            int loaderId,
                                            int maxDepth,
                                            int maxCount,
                                            long timeoutSeconds) {
        if (sessionRegistry == null) {
            return;
        }
        CommandContext ctx = CommandContextHolder.get();
        EnhancementSessionDescriptor.Builder builder = EnhancementSessionDescriptor
            .builder(traceId, EnhancementSessionKind.TRACE)
            .withCommandName("trace")
            .withClassPattern(classPattern)
            .withMethodPattern(methodPattern)
            .withTargetClassNames(Collections.singletonList(targetClassName))
            .withLoaderIds(Collections.singletonList(Integer.valueOf(loaderId)))
            .withDetails("maxDepth=" + maxDepth + ", maxCount=" + maxCount + ", timeoutSeconds=" + timeoutSeconds);
        if (ctx != null) {
            builder.withClientId(ctx.getClientId())
                .withClientSessionId(ctx.getSessionId());
        }
        sessionRegistry.register(builder.build(), reason -> stopTraceInternal(traceId));
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

    private static CancellationToken currentCancellationToken() {
        CommandContext ctx = CommandContextHolder.get();
        return ctx != null ? ctx.getCancellationToken() : CancellationToken.NONE;
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

    private static boolean hasFlag(String[] args, String flag) {
        if (args == null || flag == null || flag.isEmpty()) {
            return false;
        }
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
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
