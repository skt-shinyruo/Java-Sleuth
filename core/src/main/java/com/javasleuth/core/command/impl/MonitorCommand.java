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
import com.javasleuth.core.command.spec.CommandSpecOptionTokens;
import com.javasleuth.core.command.spec.CommandSpecParser;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.agent.runtime.BootstrapBridge;
import com.javasleuth.core.enhancement.session.EnhancementSessionManager;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.enhancement.MonitorEnhancer;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.spy.listener.MonitorAdviceListener;
import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.util.WildcardMatcher;
import com.javasleuth.foundation.util.StringUtils;
import java.lang.instrument.Instrumentation;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final EnhancementSessionManager sessionManager;

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
        this.sessionManager = new EnhancementSessionManager(instrumentation, transformer, spyDispatcher, sessionRegistry);
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
            String[] jobArgs = CommandSpecOptionTokens.removeOptionTokens(args, spec(), "bg");
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
        if (parsed != null && Boolean.TRUE.equals(parsed.booleanOption("bg")) && !CommandSpecOptionTokens.hasOptionToken(args, spec(), "bg")) {
            return CommandSpecParser.parse(spec(), args);
        }
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
        MonitorAdviceListener monitorListener = new MonitorAdviceListener();
        monitorListener.clear();
        EnhancementSessionManager.Request request = EnhancementSessionManager.Request.builder(monitorId, EnhancementSessionKind.MONITOR)
            .withCommandName("monitor")
            .withListenerKind(SleuthSpyDispatcher.ListenerKind.MONITOR)
            .withListener(monitorListener)
            .withClassPattern(classPattern)
            .withMethodPattern(methodPattern)
            .withDetails("intervalMs=" + intervalMs + ", rounds=" + rounds);
        for (Class<?> c : matches) {
            request.withTarget(c, new MonitorEnhancer(c.getName(), methodPattern, null, monitorId));
        }
        EnhancementSessionManager.ManagedSession session = sessionManager.open(request.build());

        StringBuilder banner = new StringBuilder();
        banner.append("Started monitor ").append(classPattern).append(".").append(methodPattern).append("\n");
        banner.append("Monitor ID: ").append(monitorId).append("\n");
        banner.append("Classes instrumented: ").append(matches.size()).append("\n");
        banner.append("Interval: ").append(intervalMs).append("ms, Rounds: ").append(rounds).append("\n");

        if (sink != null) {
            sink.send(banner.toString().trim());
        }

        StringBuilder out = new StringBuilder();
        int done = 0;
        CancellationToken token = currentCancellationToken();
        try {
            for (int i = 0; i < rounds && !token.isCancelled() && !session.isClosed(); i++) {
                try {
                    Thread.sleep(Math.max(1, intervalMs));
                    token.throwIfCancelled();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    appendOrSend(out, sink, "\nMonitor interrupted");
                    break;
                }

                Map<String, MonitorAdviceListener.MethodStatsSnapshot> snap;
                snap = !session.isClosed()
                    ? monitorListener.snapshot()
                    : java.util.Collections.<String, MonitorAdviceListener.MethodStatsSnapshot>emptyMap();
                appendOrSend(out, sink, formatSnapshot(snap, i + 1, rounds));
                monitorListener.clear();
                done++;
            }
        } finally {
            session.close("completed");
        }

        String summary = "Monitor completed. rounds=" + done;
        if (sink != null) {
            sink.close(summary);
            return "";
        }
        out.append(summary);
        return out.toString();
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
}
