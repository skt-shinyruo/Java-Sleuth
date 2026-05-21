package com.javasleuth.core.command.impl.stack;

import com.javasleuth.core.command.CancellationToken;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.MonitoringConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.bootstrap.data.StackTraceResult;
import com.javasleuth.core.enhancement.session.EnhancementSessionManager;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.StackEnhancer;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.spy.listener.StackAdviceListener;
import com.javasleuth.foundation.util.LoadedClassResolver;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class StackTraceLiteEngine {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConfigView config;
    private final SleuthSpyDispatcher spyDispatcher;
    private final EnhancementSessionManager sessionManager;

    public StackTraceLiteEngine(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        SleuthSpyDispatcher spyDispatcher
    ) {
        this(instrumentation, transformer, config, spyDispatcher, null);
    }

    public StackTraceLiteEngine(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        SleuthSpyDispatcher spyDispatcher,
        EnhancementSessionRegistry sessionRegistry
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = config;
        this.spyDispatcher = spyDispatcher;
        this.sessionManager = new EnhancementSessionManager(instrumentation, transformer, spyDispatcher, sessionRegistry);
    }

    public String start(
        String classPattern,
        String methodPattern,
        int maxCount,
        long timeoutSeconds,
        int depth,
        Integer loaderId,
        boolean firstOnly,
        int classLimit,
        StreamSink sink
    ) throws Exception {
        if (!SleuthSpyDispatcher.isInstalled(spyDispatcher)) {
            return SleuthSpyDispatcher.unavailableMessage("stack");
        }
        if (transformer == null) {
            return "Stack trace mode requires transformer, but transformer is null.";
        }

        List<LoadedClassResolver.Candidate> targets =
            resolveTargets(classPattern, loaderId, true, classLimit, firstOnly);
        if (targets.isEmpty()) {
            return "No modifiable loaded class matches pattern: " + classPattern;
        }

        String stackId = UUID.randomUUID().toString();
        MonitoringConfig monitoring = SleuthConfigParser.parse(configSnapshot()).monitoring();
        BlockingQueue<StackTraceResult> q = new LinkedBlockingQueue<>(monitoring.getWatchQueueCapacity());

        boolean dropOnFull = monitoring.isWatchDropOnFull();
        EnhancementSessionManager.Request request = EnhancementSessionManager.Request.builder(stackId, EnhancementSessionKind.STACK)
            .withCommandName("stack")
            .withListenerKind(SleuthSpyDispatcher.ListenerKind.STACK)
            .withListener(new StackAdviceListener(stackId, q, depth, dropOnFull))
            .withClassPattern(classPattern)
            .withMethodPattern(methodPattern)
            .withDetails("maxCount=" + maxCount + ", timeoutSeconds=" + timeoutSeconds + ", depth=" + depth);
        for (LoadedClassResolver.Candidate target : targets) {
            request.withTarget(
                target.getClazz(),
                new StackEnhancer(target.getClassName(), methodPattern, null, stackId)
            );
        }
        EnhancementSessionManager.ManagedSession session = sessionManager.open(
            request.build()
        );

        String banner = StackTraceLiteFormatter.buildBanner(classPattern, methodPattern, stackId, maxCount, timeoutSeconds, depth, targets);
        if (sink != null) {
            sink.send(banner);
        }

        StringBuilder out = new StringBuilder();
        int events = 0;
        long startMs = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;
        CancellationToken token = currentCancellationToken();

        try {
            while (events < maxCount && !token.isCancelled() && !session.isClosed()) {
                long remaining = timeoutMs - (System.currentTimeMillis() - startMs);
                if (remaining <= 0) {
                    appendOrSend(out, sink, "\nStack timeout reached");
                    break;
                }
                StackTraceResult r;
                try {
                    r = q.poll(Math.min(remaining, 1000), TimeUnit.MILLISECONDS);
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
                if (session.isClosed()) {
                    break;
                }
                if (r != null) {
                    events++;
                    appendOrSend(out, sink, StackTraceLiteFormatter.formatResult(r, events));
                }
            }
        } finally {
            session.close("completed");
        }

        String summary = "Stack completed. totalEvents=" + events;
        if (sink != null) {
            sink.close(summary);
            return "";
        }
        out.append(summary);
        return out.toString();
    }

    private ConfigView configSnapshot() {
        return config instanceof ProductionConfig ? ((ProductionConfig) config).snapshot() : config;
    }

    private List<LoadedClassResolver.Candidate> resolveTargets(
        String classPattern,
        Integer loaderId,
        boolean requireModifiable,
        int classLimit,
        boolean firstOnly
    ) {
        List<LoadedClassResolver.Candidate> candidates =
            LoadedClassResolver.findCandidates(instrumentation, classPattern, loaderId, requireModifiable, classLimit);
        if (!firstOnly || candidates.size() <= 1) {
            return candidates;
        }
        List<LoadedClassResolver.Candidate> first = new ArrayList<LoadedClassResolver.Candidate>(1);
        first.add(candidates.get(0));
        return first;
    }

    public boolean stop(String stackId) {
        return sessionManager.close(stackId, "stop");
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

}
