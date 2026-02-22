package com.javasleuth.core.command.impl.tt;

import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.bootstrap.data.TtRecord;
import com.javasleuth.core.enhancement.ClassEnhancer;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.TtEnhancer;
import com.javasleuth.bootstrap.monitor.TtInterceptor;
import com.javasleuth.foundation.util.WildcardMatcher;
import java.lang.instrument.Instrumentation;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class TtRecordEngine {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConfigView config;
    private final ConcurrentHashMap<String, TtSession> activeSessions = new ConcurrentHashMap<>();

    public TtRecordEngine(Instrumentation instrumentation, SleuthClassFileTransformer transformer, ConfigView config) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = config;
    }

    public String record(
        String classPattern,
        String methodPattern,
        int maxCount,
        long timeoutSeconds,
        StreamSink sink
    ) throws Exception {
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
        BlockingQueue<TtRecord> q = new LinkedBlockingQueue<>(config.getInt("monitoring.watch.queue.capacity", 1000));

        ClassEnhancer enhancer = new TtEnhancer(target.getName(), methodPattern, null, ttId);
        boolean interceptorRegistered = false;
        boolean enhancerAdded = false;
        try {
            TtInterceptor.register(ttId, q);
            interceptorRegistered = true;

            transformer.addEnhancer(target, enhancer);
            enhancerAdded = true;

            instrumentation.retransformClasses(target);

            TtSession session = new TtSession(ttId, target, methodPattern, q, enhancer);
            activeSessions.put(ttId, session);
        } catch (Exception e) {
            // Rollback partial state best-effort.
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
            if (interceptorRegistered) {
                try {
                    TtInterceptor.unregister(ttId);
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
                cleanupKey = "tt:" + ttId;
                clientSession.registerCleanup(cleanupKey, () -> stop(ttId));
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

        try {
            while (recorded < maxCount) {
                long remaining = timeoutMs - (System.currentTimeMillis() - startMs);
                if (remaining <= 0) {
                    appendOrSend(out, sink, "\nTT timeout reached");
                    break;
                }
                TtRecord r = q.poll(Math.min(remaining, 1000), TimeUnit.MILLISECONDS);
                if (r != null) {
                    recorded++;
                    appendOrSend(out, sink, TtFormatter.formatRecordLine(r, recorded));
                }
            }
        } finally {
            stop(ttId);
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

    public boolean stop(String ttId) {
        TtSession session = activeSessions.remove(ttId);
        if (session == null) {
            TtInterceptor.unregister(ttId);
            return false;
        }
        try {
            transformer.removeEnhancer(session.target, session.enhancer);
            instrumentation.retransformClasses(session.target);
        } catch (Exception ignored) {
            // best-effort
        } finally {
            TtInterceptor.unregister(ttId);
        }
        return true;
    }

    private void appendOrSend(StringBuilder buf, StreamSink sink, String text) {
        if (sink != null) {
            sink.send(text);
        } else {
            buf.append(text).append("\n");
        }
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
