package com.javasleuth.core.command.impl.tt;

import com.javasleuth.core.command.CancellationToken;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.MonitoringConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.bootstrap.data.TtRecord;
import com.javasleuth.core.enhancement.session.EnhancementSessionManager;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.TtEnhancer;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.spy.listener.TtAdviceListener;
import com.javasleuth.foundation.util.LoadedClassResolver;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class TtRecordEngine {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConfigView config;
    private final TtRecordStore recordStore;
    private final SleuthSpyDispatcher spyDispatcher;
    private final EnhancementSessionManager sessionManager;

    public TtRecordEngine(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        TtRecordStore recordStore,
        SleuthSpyDispatcher spyDispatcher
    ) {
        this(instrumentation, transformer, config, recordStore, spyDispatcher, null);
    }

    public TtRecordEngine(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        TtRecordStore recordStore,
        SleuthSpyDispatcher spyDispatcher,
        EnhancementSessionRegistry sessionRegistry
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = config;
        if (recordStore == null) {
            throw new IllegalArgumentException("recordStore");
        }
        this.recordStore = recordStore;
        this.spyDispatcher = spyDispatcher;
        this.sessionManager = new EnhancementSessionManager(instrumentation, transformer, spyDispatcher, sessionRegistry);
    }

    public String record(
        String classPattern,
        String methodPattern,
        int maxCount,
        long timeoutSeconds,
        Integer loaderId,
        boolean firstOnly,
        int classLimit,
        StreamSink sink
    ) throws Exception {
        if (!SleuthSpyDispatcher.isInstalled(spyDispatcher)) {
            return SleuthSpyDispatcher.unavailableMessage("tt");
        }
        List<LoadedClassResolver.Candidate> targets =
            resolveTargets(classPattern, loaderId, true, classLimit, firstOnly);
        if (targets.isEmpty()) {
            return "No modifiable loaded class matches pattern: " + classPattern;
        }

        String ttId = UUID.randomUUID().toString();
        MonitoringConfig monitoring = SleuthConfigParser.parse(configSnapshot()).monitoring();
        BlockingQueue<TtRecord> q = new LinkedBlockingQueue<>(monitoring.getWatchQueueCapacity());

        boolean dropOnFull = monitoring.isWatchDropOnFull();
        EnhancementSessionManager.Request request = EnhancementSessionManager.Request.builder(ttId, EnhancementSessionKind.TT)
            .withCommandName("tt")
            .withListenerKind(SleuthSpyDispatcher.ListenerKind.TT)
            .withListener(new TtAdviceListener(ttId, q, recordStore, dropOnFull))
            .withClassPattern(classPattern)
            .withMethodPattern(methodPattern)
            .withDetails("maxCount=" + maxCount + ", timeoutSeconds=" + timeoutSeconds);
        for (LoadedClassResolver.Candidate target : targets) {
            request.withTarget(
                target.getClazz(),
                new TtEnhancer(target.getClassName(), methodPattern, null, ttId)
            );
        }
        EnhancementSessionManager.ManagedSession session = sessionManager.open(
            request.build()
        );

        StringBuilder banner = new StringBuilder();
        banner.append("Started tt record ").append(classPattern).append(".").append(methodPattern).append("\n");
        banner.append("TT ID: ").append(ttId).append("\n");
        banner.append("Classes instrumented: ").append(targets.size()).append("\n");
        appendTargetSummary(banner, targets, 5);
        banner.append("Max records: ").append(maxCount).append(", Timeout: ").append(timeoutSeconds).append("s\n");

        if (sink != null) {
            sink.send(banner.toString().trim());
        }

        StringBuilder out = new StringBuilder();
        int recorded = 0;
        long startMs = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;
        CancellationToken token = currentCancellationToken();

        try {
            while (recorded < maxCount && !token.isCancelled() && !session.isClosed()) {
                long remaining = timeoutMs - (System.currentTimeMillis() - startMs);
                if (remaining <= 0) {
                    appendOrSend(out, sink, "\nTT timeout reached");
                    break;
                }
                TtRecord r;
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
                    recorded++;
                    appendOrSend(out, sink, TtFormatter.formatRecordLine(r, recorded));
                }
            }
        } finally {
            session.close("completed");
        }

        String summary = "TT completed. totalRecords=" + recorded;
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

    private static void appendTargetSummary(StringBuilder out, List<LoadedClassResolver.Candidate> targets, int maxLines) {
        int limit = maxLines <= 0 ? 5 : maxLines;
        int shown = 0;
        for (LoadedClassResolver.Candidate target : targets) {
            if (target == null || shown >= limit) {
                continue;
            }
            out.append("  - ").append(target.getClassName())
                .append(" (loaderId=").append(LoadedClassResolver.formatLoaderId(target.getLoaderId())).append(")")
                .append("\n");
            shown++;
        }
        if (targets.size() > shown) {
            out.append("  ... ").append(targets.size() - shown).append(" more\n");
        }
    }

    public boolean stop(String ttId) {
        return sessionManager.close(ttId, "stop");
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
