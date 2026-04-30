package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.CancellationToken;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
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
import com.javasleuth.core.enhancement.WatchEnhancer;
import com.javasleuth.bootstrap.data.WatchResult;
import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.spy.listener.WatchAdviceListener;
import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.util.LoadedClassResolver;
import com.javasleuth.core.command.util.SleuthConditionEvaluator;
import com.javasleuth.foundation.util.SleuthLogger;
import com.javasleuth.bootstrap.util.SleuthValueFormatter;
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

public class WatchCommand implements StreamCommand, SpecBackedCommand {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConfigView config;
    private final JobManager jobManager;
    private final SleuthSpyDispatcher spyDispatcher;
    private final EnhancementSessionRegistry sessionRegistry;
    private final ConcurrentHashMap<String, WatchSession> activeSessions = new ConcurrentHashMap<>();

    public WatchCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager
    ) {
        this(instrumentation, transformer, config, jobManager, null);
    }

    public WatchCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager,
        SleuthSpyDispatcher spyDispatcher
    ) {
        this(instrumentation, transformer, config, jobManager, spyDispatcher, null);
    }

    public WatchCommand(
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
        return runWatch(args, null);
    }

    @Override
    public void executeStream(String[] args, StreamSink sink) throws Exception {
        runWatch(args, sink);
    }

    public static CommandSpec spec() {
        return CommandSpec.builder("watch")
            .description("Watch method execution with parameters, return values, and timing")
            .usage("watch <class-pattern> <method-pattern> [options]")
            .meta(instrumentationStreamMeta())
            .argument(ArgumentSpec.required("class-pattern"))
            .argument(ArgumentSpec.required("method-pattern"))
            .option(OptionSpec.integer("count").alias("-n").alias("--count").defaultValue(100).range(1, 100000).build())
            .option(OptionSpec.longNumber("timeout").alias("-t").alias("--timeout").defaultValue(30L).range(1, 86400).build())
            .option(OptionSpec.string("loader").alias("--loader").alias("--loader-id").alias("--loader-hash").build())
            .option(OptionSpec.flag("first").alias("--first").alias("--unsafe-first").build())
            .option(OptionSpec.string("expr").alias("--expr").build())
            .option(OptionSpec.string("condition").alias("--condition").repeatable(true).build())
            .option(OptionSpec.flag("bg").alias("--bg").build())
            .option(OptionSpec.flag("no-params").alias("--no-params").build())
            .option(OptionSpec.flag("no-return").alias("--no-return").build())
            .option(OptionSpec.flag("no-exception").alias("--no-exception").build())
            .example("watch com.example.* execute*")
            .example("watch *Service* *method* -n 50 -t 60 --condition cost:gt:1000000")
            .example("watch MyClass doWork --no-params")
            .build();
    }

    @Override
    public CommandSpec getSpec() {
        return spec();
    }

    private String runWatch(String[] args, StreamSink sink) throws Exception {
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

        String classPattern = parsed.argument("class-pattern");
        String methodPattern = parsed.argument("method-pattern");
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
        boolean captureParams = !Boolean.TRUE.equals(parsed.booleanOption("no-params"));
        boolean captureReturn = !Boolean.TRUE.equals(parsed.booleanOption("no-return"));
        boolean captureException = !Boolean.TRUE.equals(parsed.booleanOption("no-exception"));

        if (Boolean.TRUE.equals(parsed.booleanOption("bg"))) {
            String[] jobArgs = removeFlag(args, "--bg");
            String commandLine = String.join(" ", jobArgs);
            String jobId = jobManager.submitStreamJob(
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

        List<String> expr = parseExpr(parsed.stringOption("expr"));
        List<SleuthConditionEvaluator.Condition> conditions = SleuthConditionEvaluator.parseConditions(parsed.stringOptionValues("condition"));

        return startWatching(classPattern, methodPattern, captureParams, captureReturn,
            captureException, maxCount, timeoutSeconds, loaderId, allowFirstWhenAmbiguous, expr, conditions, sink);
    }

    private ParsedCommand parsedOrFallback(String[] args) {
        CommandContext ctx = CommandContextHolder.get();
        ParsedCommand parsed = ctx != null ? ctx.getParsedCommand() : null;
        return parsed != null ? parsed : CommandSpecParser.parse(spec(), args);
    }

    private static CommandMeta instrumentationStreamMeta() {
        return CommandMeta.operator(false, true)
            .requiresBootstrap(BootstrapBridge.SPY_API)
            .withCapability(CommandCapability.LONG_RUNNING);
    }

    private String startWatching(String classPattern, String methodPattern,
                               boolean captureParams, boolean captureReturn, boolean captureException,
                               int maxCount, long timeoutSeconds,
                               Integer loaderId,
                               boolean allowFirstWhenAmbiguous,
                               List<String> expr,
                               List<SleuthConditionEvaluator.Condition> conditions,
                                StreamSink sink) throws Exception {
        if (!SleuthSpyDispatcher.isInstalled(spyDispatcher)) {
            return SleuthSpyDispatcher.unavailableMessage("watch");
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

        String watchId = UUID.randomUUID().toString();
        MonitoringConfig monitoring = SleuthConfigParser.parse(configSnapshot()).monitoring();
        BlockingQueue<WatchResult> resultQueue = new LinkedBlockingQueue<>(monitoring.getWatchQueueCapacity());
        if (targetClass == null) {
            String msg = "Target class not found in loaded classes: " + targetClassName;
            if (sink != null) {
                sink.error(msg);
                return "";
            }
            return msg;
        }

        WatchEnhancer enhancer = new WatchEnhancer(targetClassName, methodPattern, null,
            captureParams, captureReturn, captureException, watchId);
        boolean enhancerAdded = false;
        try {
            boolean dropOnFull = monitoring.isWatchDropOnFull();
            spyDispatcher.register(
                watchId,
                SleuthSpyDispatcher.ListenerKind.WATCH,
                new WatchAdviceListener(watchId, resultQueue, dropOnFull)
            );

            // Create and register enhancer
            transformer.addEnhancer(targetClass, enhancer);
            enhancerAdded = true;

            // Retransform the class
            instrumentation.retransformClasses(targetClass);

            // Create watch session
            WatchSession session = new WatchSession(watchId, targetClass, targetClassName, methodPattern, resultQueue, enhancer);
            activeSessions.put(watchId, session);
            registerEnhancementSession(
                watchId,
                classPattern,
                methodPattern,
                targetClassName,
                resolved.getLoaderId(),
                maxCount,
                timeoutSeconds
            );
        } catch (Exception e) {
            // Rollback partial state best-effort.
            activeSessions.remove(watchId);
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
                spyDispatcher.unregister(watchId);
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
                cleanupKey = "watch:" + watchId;
                clientSession.registerCleanup(cleanupKey, () -> closeWatchSession(watchId, "client_cleanup"));
            }
        } catch (Exception ignore) {
            // ignore
        }

        StringBuilder result = new StringBuilder();
        result.append("Started watching ").append(targetClassName)
            .append(" (loaderId=").append(LoadedClassResolver.formatLoaderId(resolved.getLoaderId())).append(")")
            .append(".").append(methodPattern).append("\n");
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
        CancellationToken token = currentCancellationToken();

        try {
            while (eventCount < maxCount && !token.isCancelled()) {
                long remainingTime = timeoutMs - (System.currentTimeMillis() - startTime);
                if (remainingTime <= 0) {
                    appendOrSend(result, sink, "\nWatch timeout reached");
                    break;
                }

                WatchResult watchResult;
                try {
                    watchResult = resultQueue.poll(Math.min(remainingTime, 1000), TimeUnit.MILLISECONDS);
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
            closeWatchSession(watchId, "completed");
            if (clientSession != null && cleanupKey != null) {
                clientSession.removeCleanup(cleanupKey);
            }
        }

        String summary = "Watch completed. Total events: " + eventCount;
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

    private void closeWatchSession(String watchId, String reason) {
        if (sessionRegistry != null && sessionRegistry.close(watchId, reason)) {
            return;
        }
        stopWatchInternal(watchId);
    }

    private void stopWatchInternal(String watchId) {
        WatchSession session = activeSessions.remove(watchId);
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

                // Unregister watch
                try {
                    spyDispatcher.unregister(watchId);
                } catch (Exception ignore) {
                    // ignore
                }

            } catch (Exception e) {
                SleuthLogger.warn("Error stopping watch: " + e.getMessage(), e);
            }
        }
    }

    private void registerEnhancementSession(String watchId,
                                            String classPattern,
                                            String methodPattern,
                                            String targetClassName,
                                            int loaderId,
                                            int maxCount,
                                            long timeoutSeconds) {
        if (sessionRegistry == null) {
            return;
        }
        CommandContext ctx = CommandContextHolder.get();
        EnhancementSessionDescriptor.Builder builder = EnhancementSessionDescriptor
            .builder(watchId, EnhancementSessionKind.WATCH)
            .withCommandName("watch")
            .withClassPattern(classPattern)
            .withMethodPattern(methodPattern)
            .withTargetClassNames(Collections.singletonList(targetClassName))
            .withLoaderIds(Collections.singletonList(Integer.valueOf(loaderId)))
            .withDetails("maxCount=" + maxCount + ", timeoutSeconds=" + timeoutSeconds);
        if (ctx != null) {
            builder.withClientId(ctx.getClientId())
                .withClientSessionId(ctx.getSessionId());
        }
        sessionRegistry.register(builder.build(), reason -> stopWatchInternal(watchId));
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

        if (keys.contains("params") && result.getEventType() == WatchResult.EventType.METHOD_ENTRY) {
            if (!result.isParametersCaptured()) {
                sb.append(" params=<not captured>");
            } else {
                sb.append(" params=").append(SleuthValueFormatter.format(result.getParameters(), opt));
            }
        }
        if (keys.contains("return") && result.getEventType() == WatchResult.EventType.METHOD_EXIT) {
            if (!result.isReturnCaptured()) {
                sb.append(" return=<not captured>");
            } else {
                sb.append(" return=").append(SleuthValueFormatter.format(result.getReturnValue(), opt));
            }
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

    @Override
    public String getDescription() {
        return "Watch method execution with parameters, return values, and timing";
    }

    private static class WatchSession {
        private final String watchId;
        private final Class<?> targetClass;
        private final String className;
        private final String methodPattern;
        private final BlockingQueue<WatchResult> resultQueue;
        private final ClassEnhancer enhancer;

        public WatchSession(String watchId, Class<?> targetClass, String className, String methodPattern,
                            BlockingQueue<WatchResult> resultQueue, ClassEnhancer enhancer) {
            this.watchId = watchId;
            this.targetClass = targetClass;
            this.className = className;
            this.methodPattern = methodPattern;
            this.resultQueue = resultQueue;
            this.enhancer = enhancer;
        }

        public String getWatchId() { return watchId; }
        public Class<?> getTargetClass() { return targetClass; }
        public String getClassName() { return className; }
        public String getMethodPattern() { return methodPattern; }
        public BlockingQueue<WatchResult> getResultQueue() { return resultQueue; }
        public ClassEnhancer getEnhancer() { return enhancer; }
    }
}
