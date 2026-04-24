package com.javasleuth.core.command.impl.stack;

import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.bootstrap.data.StackTraceResult;
import com.javasleuth.core.enhancement.ClassEnhancer;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.StackEnhancer;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.spy.listener.StackAdviceListener;
import com.javasleuth.foundation.util.WildcardMatcher;
import java.lang.instrument.Instrumentation;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class StackTraceLiteEngine {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConfigView config;
    private final SleuthSpyDispatcher spyDispatcher;
    private final ConcurrentHashMap<String, StackSession> activeSessions = new ConcurrentHashMap<>();

    public StackTraceLiteEngine(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        SleuthSpyDispatcher spyDispatcher
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = config;
        this.spyDispatcher = spyDispatcher;
    }

    public String start(
        String classPattern,
        String methodPattern,
        int maxCount,
        long timeoutSeconds,
        int depth,
        StreamSink sink
    ) throws Exception {
        if (!SleuthSpyDispatcher.isInstalled(spyDispatcher)) {
            return SleuthSpyDispatcher.unavailableMessage("stack");
        }
        if (transformer == null) {
            return "Stack trace mode requires transformer, but transformer is null.";
        }

        // 简化策略：只选择一个已加载的匹配类
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

        String stackId = UUID.randomUUID().toString();
        BlockingQueue<StackTraceResult> q = new LinkedBlockingQueue<>(config.getInt("monitoring.watch.queue.capacity", 1000));

        ClassEnhancer enhancer = new StackEnhancer(target.getName(), methodPattern, null, stackId);
        boolean enhancerAdded = false;
        try {
            boolean dropOnFull = config.getBoolean("monitoring.watch.drop.on.full", true);
            spyDispatcher.register(
                stackId,
                SleuthSpyDispatcher.ListenerKind.STACK,
                new StackAdviceListener(stackId, q, depth, dropOnFull)
            );

            transformer.addEnhancer(target, enhancer);
            enhancerAdded = true;

            instrumentation.retransformClasses(target);

            activeSessions.put(stackId, new StackSession(stackId, target, methodPattern, q, enhancer));
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
            try {
                spyDispatcher.unregister(stackId);
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
                cleanupKey = "stack:" + stackId;
                clientSession.registerCleanup(cleanupKey, () -> stop(stackId));
            }
        } catch (Exception ignore) {
            // ignore
        }

        String banner = StackTraceLiteFormatter.buildBanner(target, methodPattern, stackId, maxCount, timeoutSeconds, depth);
        if (sink != null) {
            sink.send(banner);
        }

        StringBuilder out = new StringBuilder();
        int events = 0;
        long startMs = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        try {
            while (events < maxCount) {
                long remaining = timeoutMs - (System.currentTimeMillis() - startMs);
                if (remaining <= 0) {
                    appendOrSend(out, sink, "\nStack timeout reached");
                    break;
                }
                StackTraceResult r = q.poll(Math.min(remaining, 1000), TimeUnit.MILLISECONDS);
                if (r != null) {
                    events++;
                    appendOrSend(out, sink, StackTraceLiteFormatter.formatResult(r, events));
                }
            }
        } finally {
            stop(stackId);
            if (clientSession != null && cleanupKey != null) {
                try {
                    clientSession.removeCleanup(cleanupKey);
                } catch (Exception ignore) {
                    // ignore
                }
            }
        }

        String summary = "Stack completed. totalEvents=" + events;
        if (sink != null) {
            sink.close(summary);
            return "";
        }
        out.append(summary);
        return out.toString();
    }

    public boolean stop(String stackId) {
        StackSession session = activeSessions.remove(stackId);
        if (session == null) {
            try {
                spyDispatcher.unregister(stackId);
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
                spyDispatcher.unregister(stackId);
            } catch (Exception ignore) {
                // ignore
            }
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

    private static final class StackSession {
        private final String stackId;
        private final Class<?> target;
        private final String methodPattern;
        private final BlockingQueue<StackTraceResult> queue;
        private final ClassEnhancer enhancer;

        private StackSession(
            String stackId,
            Class<?> target,
            String methodPattern,
            BlockingQueue<StackTraceResult> queue,
            ClassEnhancer enhancer
        ) {
            this.stackId = stackId;
            this.target = target;
            this.methodPattern = methodPattern;
            this.queue = queue;
            this.enhancer = enhancer;
        }
    }
}
