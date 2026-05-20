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
import com.javasleuth.foundation.util.WildcardMatcher;
import java.lang.instrument.Instrumentation;
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
        StreamSink sink
    ) throws Exception {
        if (!SleuthSpyDispatcher.isInstalled(spyDispatcher)) {
            return SleuthSpyDispatcher.unavailableMessage("tt");
        }
        // Find one matching class (simplified).
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

        String ttId = UUID.randomUUID().toString();
        MonitoringConfig monitoring = SleuthConfigParser.parse(configSnapshot()).monitoring();
        BlockingQueue<TtRecord> q = new LinkedBlockingQueue<>(monitoring.getWatchQueueCapacity());

        TtEnhancer enhancer = new TtEnhancer(target.getName(), methodPattern, null, ttId);
        boolean dropOnFull = monitoring.isWatchDropOnFull();
        EnhancementSessionManager.ManagedSession session = sessionManager.open(
            EnhancementSessionManager.Request.builder(ttId, EnhancementSessionKind.TT)
                .withCommandName("tt")
                .withListenerKind(SleuthSpyDispatcher.ListenerKind.TT)
                .withListener(new TtAdviceListener(ttId, q, recordStore, dropOnFull))
                .withClassPattern(classPattern)
                .withMethodPattern(methodPattern)
                .withTarget(target, enhancer)
                .withDetails("maxCount=" + maxCount + ", timeoutSeconds=" + timeoutSeconds)
                .build()
        );

        StringBuilder banner = new StringBuilder();
        banner.append("Started tt record ").append(target.getName()).append(".").append(methodPattern).append("\n");
        banner.append("TT ID: ").append(ttId).append("\n");
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
