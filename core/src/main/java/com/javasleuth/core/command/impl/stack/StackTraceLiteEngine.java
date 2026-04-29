package com.javasleuth.core.command.impl.stack;

import com.javasleuth.core.command.CancellationToken;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.bootstrap.data.StackTraceResult;
import com.javasleuth.core.enhancement.ClassEnhancer;
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.StackEnhancer;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.spy.listener.StackAdviceListener;
import com.javasleuth.foundation.util.LoadedClassResolver;
import com.javasleuth.foundation.util.WildcardMatcher;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
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
    private final EnhancementSessionRegistry sessionRegistry;
    private final ConcurrentHashMap<String, StackSession> activeSessions = new ConcurrentHashMap<>();

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
        this.sessionRegistry = sessionRegistry;
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
            registerEnhancementSession(stackId, classPattern, methodPattern, target, maxCount, timeoutSeconds, depth);
        } catch (Exception e) {
            // Rollback partial state best-effort.
            activeSessions.remove(stackId);
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
                clientSession.registerCleanup(cleanupKey, () -> closeStackSession(stackId, "client_cleanup"));
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
        CancellationToken token = currentCancellationToken();

        try {
            while (events < maxCount && !token.isCancelled()) {
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
                if (r != null) {
                    events++;
                    appendOrSend(out, sink, StackTraceLiteFormatter.formatResult(r, events));
                }
            }
        } finally {
            closeStackSession(stackId, "completed");
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
        return closeStackSession(stackId, "stop");
    }

    private boolean closeStackSession(String stackId, String reason) {
        if (sessionRegistry != null && sessionRegistry.close(stackId, reason)) {
            return true;
        }
        return stopInternal(stackId);
    }

    private boolean stopInternal(String stackId) {
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

    private void registerEnhancementSession(String stackId,
                                            String classPattern,
                                            String methodPattern,
                                            Class<?> target,
                                            int maxCount,
                                            long timeoutSeconds,
                                            int depth) {
        if (sessionRegistry == null) {
            return;
        }
        CommandContext ctx = CommandContextHolder.get();
        EnhancementSessionDescriptor.Builder builder = EnhancementSessionDescriptor
            .builder(stackId, EnhancementSessionKind.STACK)
            .withCommandName("stack")
            .withClassPattern(classPattern)
            .withMethodPattern(methodPattern)
            .withTargetClassNames(Collections.singletonList(target.getName()))
            .withLoaderIds(Collections.singletonList(Integer.valueOf(LoadedClassResolver.loaderId(target.getClassLoader()))))
            .withDetails("maxCount=" + maxCount + ", timeoutSeconds=" + timeoutSeconds + ", depth=" + depth);
        if (ctx != null) {
            builder.withClientId(ctx.getClientId())
                .withClientSessionId(ctx.getSessionId());
        }
        sessionRegistry.register(builder.build(), reason -> stopInternal(stackId));
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
