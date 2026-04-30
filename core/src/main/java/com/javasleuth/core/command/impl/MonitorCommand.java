package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.CancellationToken;
import com.javasleuth.core.command.JobManager;
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
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.core.enhancement.ClassEnhancer;
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.enhancement.MonitorEnhancer;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.spy.listener.MonitorAdviceListener;
import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.util.LoadedClassResolver;
import com.javasleuth.foundation.util.WildcardMatcher;
import com.javasleuth.foundation.util.StringUtils;
import java.lang.instrument.Instrumentation;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Method monitor (simplified Arthas-like).
 *
 * Usage:
 *   monitor <class-pattern> <method-pattern> [-i <ms>] [-n <rounds>] [--limit <classes>] [--bg]
 */
public class MonitorCommand implements StreamCommand, SpecBackedCommand {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final JobManager jobManager;
    private final SleuthSpyDispatcher spyDispatcher;
    private final EnhancementSessionRegistry sessionRegistry;
    private final ConcurrentHashMap<String, MonitorSession> activeSessions = new ConcurrentHashMap<>();

    public MonitorCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        JobManager jobManager
    ) {
        this(instrumentation, transformer, jobManager, null);
    }

    public MonitorCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        JobManager jobManager,
        SleuthSpyDispatcher spyDispatcher
    ) {
        this(instrumentation, transformer, jobManager, spyDispatcher, null);
    }

    public MonitorCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        JobManager jobManager,
        SleuthSpyDispatcher spyDispatcher,
        EnhancementSessionRegistry sessionRegistry
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        if (jobManager == null) {
            throw new IllegalArgumentException("jobManager");
        }
        this.jobManager = jobManager;
        this.spyDispatcher = spyDispatcher;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public String execute(String[] args) throws Exception {
        return runMonitor(args, null);
    }

    @Override
    public void executeStream(String[] args, StreamSink sink) throws Exception {
        runMonitor(args, sink);
    }

    public static CommandSpec spec() {
        return CommandSpec.builder("monitor")
            .description("Monitor method statistics periodically (simplified)")
            .usage("monitor <class-pattern> <method-pattern> [options]")
            .meta(instrumentationStreamMeta())
            .argument(ArgumentSpec.required("class-pattern"))
            .argument(ArgumentSpec.required("method-pattern"))
            .option(OptionSpec.longNumber("interval").alias("-i").alias("--interval").defaultValue(5000L).range(1, 86400000).build())
            .option(OptionSpec.integer("count").alias("-n").alias("--count").defaultValue(10).range(1, 100000).build())
            .option(OptionSpec.integer("limit").alias("--limit").defaultValue(50).range(1, 10000).build())
            .option(OptionSpec.flag("bg").alias("--bg").build())
            .example("monitor com.example.* execute*")
            .example("monitor *Service* *method* -i 1000 -n 20")
            .example("monitor MyClass doWork --limit 10")
            .build();
    }

    @Override
    public CommandSpec getSpec() {
        return spec();
    }

    private String runMonitor(String[] args, StreamSink sink) throws Exception {
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
        long intervalMs = parsed.longOption("interval");
        int rounds = parsed.intOption("count");
        int classLimit = parsed.intOption("limit");

        if (Boolean.TRUE.equals(parsed.booleanOption("bg"))) {
            String[] jobArgs = removeFlag(args, "--bg");
            String commandLine = String.join(" ", jobArgs);
            String jobId = jobManager.submitStreamJob(
                "monitor",
                commandLine,
                jobSink -> runMonitor(jobArgs, jobSink)
            );
            String msg = "Started monitor in background. Job ID: " + jobId + " (use: jobs tail " + jobId + ")";
            if (sink != null) {
                sink.send(msg);
                sink.close("job started");
                return "";
            }
            return msg;
        }

        return startMonitoring(classPattern, methodPattern, intervalMs, rounds, classLimit, sink);
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

    private String startMonitoring(String classPattern, String methodPattern,
                                   long intervalMs, int rounds, int classLimit,
                                   StreamSink sink) throws Exception {
        if (!SleuthSpyDispatcher.isInstalled(spyDispatcher)) {
            return SleuthSpyDispatcher.unavailableMessage("monitor");
        }
        List<Class<?>> matches = new ArrayList<>();
        for (Class<?> c : instrumentation.getAllLoadedClasses()) {
            if (c == null) {
                continue;
            }
            if (!WildcardMatcher.matches(c.getName(), classPattern)) {
                continue;
            }
            if (!instrumentation.isModifiableClass(c)) {
                continue;
            }
            matches.add(c);
            if (matches.size() >= classLimit) {
                break;
            }
        }

        if (matches.isEmpty()) {
            return "No modifiable loaded class matches pattern: " + classPattern;
        }

        String monitorId = UUID.randomUUID().toString();
        MonitorAdviceListener monitorListener = null;
        List<MonitorSession.EnhancedClass> enhanced = new ArrayList<>();
        try {
            monitorListener = new MonitorAdviceListener();
            spyDispatcher.register(monitorId, SleuthSpyDispatcher.ListenerKind.MONITOR, monitorListener);
            monitorListener.clear();

            for (Class<?> c : matches) {
                MonitorEnhancer enhancer = new MonitorEnhancer(c.getName(), methodPattern, null, monitorId);
                transformer.addEnhancer(c, enhancer);
                try {
                    instrumentation.retransformClasses(c);
                } catch (Exception e) {
                    // Roll back this class registration and then abort.
                    try {
                        transformer.removeEnhancer(c, enhancer);
                    } catch (Exception ignore) {
                        // ignore
                    }
                    try {
                        instrumentation.retransformClasses(c);
                    } catch (Exception ignore) {
                        // ignore
                    }
                    throw e;
                }
                enhanced.add(new MonitorSession.EnhancedClass(c, enhancer));
            }
        } catch (Exception e) {
            // Rollback any partial state best-effort.
            for (MonitorSession.EnhancedClass ec : enhanced) {
                try {
                    transformer.removeEnhancer(ec.clazz, ec.enhancer);
                } catch (Exception ignore) {
                    // ignore
                }
                try {
                    instrumentation.retransformClasses(ec.clazz);
                } catch (Exception ignore) {
                    // ignore
                }
            }
            try {
                spyDispatcher.unregister(monitorId);
            } catch (Exception ignore) {
                // ignore
            }
            throw e;
        }

        MonitorSession session = new MonitorSession(monitorId, classPattern, methodPattern, enhanced);
        session.monitorListener = monitorListener;
        activeSessions.put(monitorId, session);
        try {
            registerEnhancementSession(monitorId, classPattern, methodPattern, enhanced, intervalMs, rounds);
        } catch (Exception e) {
            stopMonitorInternal(monitorId);
            throw e;
        }

        ClientSession clientSession = null;
        String cleanupKey = null;
        try {
            CommandContext ctx = CommandContextHolder.get();
            clientSession = ctx != null ? ctx.getClientSession() : null;
            if (clientSession != null) {
                cleanupKey = "monitor:" + monitorId;
                clientSession.registerCleanup(cleanupKey, () -> closeMonitorSession(monitorId, "client_cleanup"));
            }
        } catch (Exception ignore) {
            // ignore
        }

        StringBuilder banner = new StringBuilder();
        banner.append("Started monitor ").append(classPattern).append(".").append(methodPattern).append("\n");
        banner.append("Monitor ID: ").append(monitorId).append("\n");
        banner.append("Classes instrumented: ").append(enhanced.size()).append("\n");
        banner.append("Interval: ").append(intervalMs).append("ms, Rounds: ").append(rounds).append("\n");

        if (sink != null) {
            sink.send(banner.toString().trim());
        }

        StringBuilder out = new StringBuilder();
        int done = 0;
        CancellationToken token = currentCancellationToken();
        try {
            for (int i = 0; i < rounds && !token.isCancelled(); i++) {
                try {
                    Thread.sleep(Math.max(1, intervalMs));
                    token.throwIfCancelled();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    appendOrSend(out, sink, "\nMonitor interrupted");
                    break;
                }

                Map<String, MonitorAdviceListener.MethodStatsSnapshot> snap;
                MonitorSession s = activeSessions.get(monitorId);
                MonitorAdviceListener l = s != null ? s.monitorListener : null;
                snap = l != null ? l.snapshot() : java.util.Collections.<String, MonitorAdviceListener.MethodStatsSnapshot>emptyMap();
                appendOrSend(out, sink, formatSnapshot(snap, i + 1, rounds));
                if (l != null) {
                    l.clear();
                }
                done++;
            }
        } finally {
            closeMonitorSession(monitorId, "completed");
            if (clientSession != null && cleanupKey != null) {
                clientSession.removeCleanup(cleanupKey);
            }
        }

        String summary = "Monitor completed. rounds=" + done;
        if (sink != null) {
            sink.close(summary);
            return "";
        }
        out.append(summary);
        return out.toString();
    }

    private void closeMonitorSession(String monitorId, String reason) {
        if (sessionRegistry != null && sessionRegistry.close(monitorId, reason)) {
            return;
        }
        stopMonitorInternal(monitorId);
    }

    private void stopMonitorInternal(String monitorId) {
        MonitorSession session = activeSessions.remove(monitorId);
        if (session == null) {
            try {
                spyDispatcher.unregister(monitorId);
            } catch (Exception ignore) {
                // ignore
            }
            return;
        }

        try {
            for (MonitorSession.EnhancedClass ec : session.enhancedClasses) {
                transformer.removeEnhancer(ec.clazz, ec.enhancer);
                try {
                    instrumentation.retransformClasses(ec.clazz);
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        } finally {
            try {
                spyDispatcher.unregister(monitorId);
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    private void registerEnhancementSession(String monitorId,
                                            String classPattern,
                                            String methodPattern,
                                            List<MonitorSession.EnhancedClass> enhanced,
                                            long intervalMs,
                                            int rounds) {
        if (sessionRegistry == null) {
            return;
        }
        List<String> targetClassNames = new ArrayList<>();
        List<Integer> loaderIds = new ArrayList<>();
        if (enhanced != null) {
            for (MonitorSession.EnhancedClass ec : enhanced) {
                if (ec == null || ec.clazz == null) {
                    continue;
                }
                targetClassNames.add(ec.className);
                loaderIds.add(Integer.valueOf(LoadedClassResolver.loaderId(ec.clazz.getClassLoader())));
            }
        }
        CommandContext ctx = CommandContextHolder.get();
        EnhancementSessionDescriptor.Builder builder = EnhancementSessionDescriptor
            .builder(monitorId, EnhancementSessionKind.MONITOR)
            .withCommandName("monitor")
            .withClassPattern(classPattern)
            .withMethodPattern(methodPattern)
            .withTargetClassNames(targetClassNames)
            .withLoaderIds(loaderIds)
            .withDetails("intervalMs=" + intervalMs + ", rounds=" + rounds);
        if (ctx != null) {
            builder.withClientId(ctx.getClientId())
                .withClientSessionId(ctx.getSessionId());
        }
        sessionRegistry.register(builder.build(), reason -> stopMonitorInternal(monitorId));
    }

    private String formatSnapshot(Map<String, MonitorAdviceListener.MethodStatsSnapshot> snap, int round, int rounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Monitor Stats === ").append(LocalTime.now()).append(" (").append(round).append("/").append(rounds).append(")\n");
        if (snap == null || snap.isEmpty()) {
            sb.append("(no calls)\n");
            return sb.toString().trim();
        }

        List<Map.Entry<String, MonitorAdviceListener.MethodStatsSnapshot>> rows = new ArrayList<>(snap.entrySet());
        rows.sort(Comparator.comparingLong((Map.Entry<String, MonitorAdviceListener.MethodStatsSnapshot> e) -> e.getValue().getTotalNanos()).reversed());

        sb.append(String.format("%-60s %8s %8s %10s %10s %10s\n", "METHOD", "COUNT", "EX", "AVG(ms)", "MAX(ms)", "MIN(ms)"));
        sb.append(StringUtils.repeat('=', 120)).append("\n");

        int shown = 0;
        for (Map.Entry<String, MonitorAdviceListener.MethodStatsSnapshot> e : rows) {
            if (shown >= 20) {
                break;
            }
            MonitorAdviceListener.MethodStatsSnapshot s = e.getValue();
            long count = s.getCount();
            double avgMs = count <= 0 ? 0.0 : (s.getTotalNanos() / 1_000_000.0) / count;
            sb.append(String.format("%-60s %8d %8d %10.2f %10.2f %10.2f\n",
                truncate(e.getKey(), 60),
                count,
                s.getExceptionCount(),
                avgMs,
                s.getMaxNanos() / 1_000_000.0,
                s.getMinNanos() / 1_000_000.0
            ));
            shown++;
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

    private static CancellationToken currentCancellationToken() {
        CommandContext ctx = CommandContextHolder.get();
        return ctx != null ? ctx.getCancellationToken() : CancellationToken.NONE;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    @Override
    public String getDescription() {
        return "Monitor method statistics periodically (simplified)";
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

    private static final class MonitorSession {
        private final String id;
        private final String classPattern;
        private final String methodPattern;
        private final List<EnhancedClass> enhancedClasses;
        private MonitorAdviceListener monitorListener;

        private MonitorSession(String id, String classPattern, String methodPattern, List<EnhancedClass> enhancedClasses) {
            this.id = id;
            this.classPattern = classPattern;
            this.methodPattern = methodPattern;
            this.enhancedClasses = enhancedClasses;
        }

        private static final class EnhancedClass {
            private final Class<?> clazz;
            private final String className;
            private final ClassEnhancer enhancer;

            private EnhancedClass(Class<?> clazz, ClassEnhancer enhancer) {
                this.clazz = clazz;
                this.className = clazz != null ? clazz.getName() : "";
                this.enhancer = enhancer;
            }
        }
    }
}
