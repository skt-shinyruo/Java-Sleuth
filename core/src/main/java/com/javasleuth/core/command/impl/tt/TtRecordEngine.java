package com.javasleuth.core.command.impl.tt;

import com.javasleuth.core.command.CancellationToken;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.MonitoringConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.bootstrap.data.TtRecord;
import com.javasleuth.core.enhancement.ClassEnhancer;
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.TtEnhancer;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.spy.listener.TtAdviceListener;
import com.javasleuth.foundation.util.LoadedClassResolver;
import com.javasleuth.foundation.util.WildcardMatcher;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class TtRecordEngine {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConfigView config;
    private final TtRecordStore recordStore;
    private final SleuthSpyDispatcher spyDispatcher;
    private final EnhancementSessionRegistry sessionRegistry;
    private final ConcurrentHashMap<String, TtSession> activeSessions = new ConcurrentHashMap<>();

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
        this.sessionRegistry = sessionRegistry;
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

        ClassEnhancer enhancer = new TtEnhancer(target.getName(), methodPattern, null, ttId);
        boolean enhancerAdded = false;
        try {
            boolean dropOnFull = monitoring.isWatchDropOnFull();
            spyDispatcher.register(
                ttId,
                SleuthSpyDispatcher.ListenerKind.TT,
                new TtAdviceListener(ttId, q, recordStore, dropOnFull)
            );

            transformer.addEnhancer(target, enhancer);
            enhancerAdded = true;

            instrumentation.retransformClasses(target);

            TtSession session = new TtSession(ttId, target, methodPattern, q, enhancer);
            activeSessions.put(ttId, session);
            registerEnhancementSession(ttId, classPattern, methodPattern, target, maxCount, timeoutSeconds);
        } catch (Exception e) {
            // Rollback partial state best-effort.
            activeSessions.remove(ttId);
            if (enhancerAdded) {
                try {
                    transformer.removeEnhancer(target, enhancer);
                } catch (Exception ignore) {
                    // ignore
                }
                try {
                    instrumentation.retransformClasses(target);
                } catch (Exception ignore) {
                    // ignore
                }
            }
            try {
                spyDispatcher.unregister(ttId);
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
                cleanupKey = "tt:" + ttId;
                clientSession.registerCleanup(cleanupKey, () -> closeTtSession(ttId, "client_cleanup"));
            }
        } catch (Exception ignore) {
            // ignore
        }

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
            while (recorded < maxCount && !token.isCancelled()) {
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
                if (r != null) {
                    recorded++;
                    appendOrSend(out, sink, TtFormatter.formatRecordLine(r, recorded));
                }
            }
        } finally {
            closeTtSession(ttId, "completed");
            if (clientSession != null && cleanupKey != null) {
                try {
                    clientSession.removeCleanup(cleanupKey);
                } catch (Exception ignore) {
                    // ignore
                }
            }
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
        return closeTtSession(ttId, "stop");
    }

    private boolean closeTtSession(String ttId, String reason) {
        if (sessionRegistry != null && sessionRegistry.close(ttId, reason)) {
            return true;
        }
        return stopInternal(ttId);
    }

    private boolean stopInternal(String ttId) {
        TtSession session = activeSessions.remove(ttId);
        if (session == null) {
            try {
                spyDispatcher.unregister(ttId);
            } catch (Exception ignore) {
                // ignore
            }
            return false;
        }
        try {
            transformer.removeEnhancer(session.target, session.enhancer);
            instrumentation.retransformClasses(session.target);
        } catch (Exception ignored) {
            // best-effort
        } finally {
            try {
                spyDispatcher.unregister(ttId);
            } catch (Exception ignore) {
                // ignore
            }
        }
        return true;
    }

    private void registerEnhancementSession(String ttId,
                                            String classPattern,
                                            String methodPattern,
                                            Class<?> target,
                                            int maxCount,
                                            long timeoutSeconds) {
        if (sessionRegistry == null) {
            return;
        }
        CommandContext ctx = CommandContextHolder.get();
        EnhancementSessionDescriptor.Builder builder = EnhancementSessionDescriptor
            .builder(ttId, EnhancementSessionKind.TT)
            .withCommandName("tt")
            .withClassPattern(classPattern)
            .withMethodPattern(methodPattern)
            .withTargetClassNames(Collections.singletonList(target.getName()))
            .withLoaderIds(Collections.singletonList(Integer.valueOf(LoadedClassResolver.loaderId(target.getClassLoader()))))
            .withDetails("maxCount=" + maxCount + ", timeoutSeconds=" + timeoutSeconds);
        if (ctx != null) {
            builder.withClientId(ctx.getClientId())
                .withClientSessionId(ctx.getSessionId());
        }
        sessionRegistry.register(builder.build(), reason -> stopInternal(ttId));
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

    private static final class TtSession {
        private final String ttId;
        private final Class<?> target;
        private final String methodPattern;
        private final BlockingQueue<TtRecord> queue;
        private final ClassEnhancer enhancer;

        private TtSession(
            String ttId,
            Class<?> target,
            String methodPattern,
            BlockingQueue<TtRecord> queue,
            ClassEnhancer enhancer
        ) {
            this.ttId = ttId;
            this.target = target;
            this.methodPattern = methodPattern;
            this.queue = queue;
            this.enhancer = enhancer;
        }
    }
}
