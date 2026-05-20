package com.javasleuth.core.enhancement.session;

import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.core.enhancement.ClassEnhancer;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.spy.SleuthAdviceListener;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.foundation.util.LoadedClassResolver;
import com.javasleuth.foundation.util.SleuthLogger;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the common lifecycle for listener-backed bytecode enhancement sessions.
 */
public final class EnhancementSessionManager {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final SleuthSpyDispatcher spyDispatcher;
    private final EnhancementSessionRegistry registry;
    private final ConcurrentHashMap<String, ManagedSession> activeSessions = new ConcurrentHashMap<String, ManagedSession>();

    public EnhancementSessionManager(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        SleuthSpyDispatcher spyDispatcher,
        EnhancementSessionRegistry registry
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.spyDispatcher = spyDispatcher;
        this.registry = registry;
    }

    public ManagedSession open(Request request) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        request.validate();
        close(request.sessionId, "replaced");
        List<Target> enhanced = new ArrayList<Target>();
        boolean listenerRegistered = false;
        ManagedSession session = null;
        try {
            spyDispatcher.register(request.sessionId, request.listenerKind, request.listener);
            listenerRegistered = true;

            for (Target target : request.targets) {
                transformer.addEnhancer(target.clazz, target.enhancer);
                enhanced.add(target);
                instrumentation.retransformClasses(target.clazz);
            }

            session = new ManagedSession(request, enhanced);
            activeSessions.put(request.sessionId, session);

            if (registry != null) {
                final ManagedSession registeredSession = session;
                registry.register(descriptorFor(request, enhanced), reason -> registeredSession.closeInternal(reason));
            }
            session.registerClientCleanup();
            return session;
        } catch (Exception e) {
            closeOrRollback(request, enhanced, listenerRegistered, session);
            throw e;
        } catch (Error e) {
            closeOrRollback(request, enhanced, listenerRegistered, session);
            throw e;
        }
    }

    public boolean close(String sessionId, String reason) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return false;
        }
        if (registry != null && registry.close(sessionId, reason)) {
            return true;
        }
        ManagedSession session = activeSessions.get(sessionId.trim());
        return session != null && session.closeInternal(reason);
    }

    public int activeCount() {
        return activeSessions.size();
    }

    private EnhancementSessionDescriptor descriptorFor(Request request, List<Target> enhanced) {
        CommandContext ctx = CommandContextHolder.get();
        EnhancementSessionDescriptor.Builder builder = EnhancementSessionDescriptor
            .builder(request.sessionId, request.kind)
            .withCommandName(request.commandName)
            .withClassPattern(request.classPattern)
            .withMethodPattern(request.methodPattern)
            .withTargetClassNames(targetClassNames(enhanced))
            .withLoaderIds(loaderIds(enhanced))
            .withBackgroundJobId(request.backgroundJobId)
            .withDetails(request.details);
        if (ctx != null) {
            builder.withClientId(ctx.getClientId())
                .withClientSessionId(ctx.getSessionId());
        }
        return builder.build();
    }

    private static List<String> targetClassNames(List<Target> targets) {
        if (targets == null || targets.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (Target target : targets) {
            if (target != null && target.clazz != null) {
                out.add(target.clazz.getName());
            }
        }
        return out;
    }

    private static List<Integer> loaderIds(List<Target> targets) {
        if (targets == null || targets.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> out = new ArrayList<Integer>();
        for (Target target : targets) {
            if (target != null && target.clazz != null) {
                out.add(Integer.valueOf(LoadedClassResolver.loaderId(target.clazz.getClassLoader())));
            }
        }
        return out;
    }

    private void rollback(List<Target> enhanced) {
        if (enhanced == null || enhanced.isEmpty()) {
            return;
        }
        for (Target target : enhanced) {
            if (target == null) {
                continue;
            }
            removeEnhancer(target);
            retransformBestEffort(target.clazz);
        }
    }

    private void removeEnhancer(Target target) {
        try {
            transformer.removeEnhancer(target.clazz, target.enhancer);
        } catch (Exception ignore) {
            // best-effort rollback
        }
    }

    private void retransformBestEffort(Class<?> clazz) {
        if (clazz == null) {
            return;
        }
        try {
            instrumentation.retransformClasses(clazz);
        } catch (Exception ignore) {
            // best-effort rollback
        }
    }

    private void unregisterListener(String sessionId) {
        try {
            spyDispatcher.unregister(sessionId);
        } catch (Exception ignore) {
            // best-effort cleanup
        }
    }

    private void closeOrRollback(Request request, List<Target> enhanced, boolean listenerRegistered, ManagedSession session) {
        if (session != null) {
            session.closeInternal("open_failed");
            return;
        }
        rollback(enhanced);
        if (listenerRegistered) {
            unregisterListener(request.sessionId);
        }
    }

    public final class ManagedSession implements AutoCloseable {
        private final Request request;
        private final List<Target> enhanced;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile ClientSession clientSession;
        private volatile String cleanupKey;

        private ManagedSession(Request request, List<Target> enhanced) {
            this.request = request;
            this.enhanced = Collections.unmodifiableList(new ArrayList<Target>(enhanced));
        }

        public String getSessionId() {
            return request.sessionId;
        }

        public EnhancementSessionKind getKind() {
            return request.kind;
        }

        public boolean isClosed() {
            return closed.get();
        }

        public boolean close(String reason) {
            return EnhancementSessionManager.this.close(request.sessionId, reason);
        }

        @Override
        public void close() {
            close("close");
        }

        private void registerClientCleanup() {
            CommandContext ctx = CommandContextHolder.get();
            ClientSession current = ctx != null ? ctx.getClientSession() : null;
            if (current == null) {
                return;
            }
            String key = cleanupKey(request);
            clientSession = current;
            cleanupKey = key;
            current.registerCleanup(key, () -> EnhancementSessionManager.this.close(request.sessionId, "client_cleanup"));
        }

        private boolean closeInternal(String reason) {
            if (!closed.compareAndSet(false, true)) {
                return false;
            }
            activeSessions.remove(request.sessionId, this);
            runOnClose(reason);
            for (Target target : enhanced) {
                removeEnhancer(target);
                retransformBestEffort(target.clazz);
            }
            unregisterListener(request.sessionId);
            ClientSession current = clientSession;
            String key = cleanupKey;
            if (current != null && key != null) {
                try {
                    current.removeCleanup(key);
                } catch (Exception ignore) {
                    // best-effort cleanup
                }
            }
            return true;
        }

        private void runOnClose(String reason) {
            if (request.onClose == null) {
                return;
            }
            try {
                request.onClose.close(reason);
            } catch (Exception e) {
                SleuthLogger.warn("Error closing enhancement session " + request.sessionId + ": " + e.getMessage(), e);
            }
        }
    }

    private static String cleanupKey(Request request) {
        String command = request.commandName;
        if (command == null || command.trim().isEmpty()) {
            command = request.kind != null ? request.kind.name().toLowerCase(Locale.ROOT) : "enhancement";
        }
        return command.trim() + ":" + request.sessionId;
    }

    public interface CloseHook {
        void close(String reason) throws Exception;
    }

    public static final class Request {
        private final String sessionId;
        private final EnhancementSessionKind kind;
        private SleuthSpyDispatcher.ListenerKind listenerKind = SleuthSpyDispatcher.ListenerKind.OTHER;
        private SleuthAdviceListener listener;
        private String commandName;
        private String classPattern;
        private String methodPattern;
        private String backgroundJobId;
        private String details;
        private CloseHook onClose;
        private final List<Target> targets = new ArrayList<Target>();

        private Request(String sessionId, EnhancementSessionKind kind) {
            this.sessionId = trimToNull(sessionId);
            this.kind = kind != null ? kind : EnhancementSessionKind.OTHER;
            this.commandName = defaultCommandName(this.kind);
        }

        public static Request builder(String sessionId, EnhancementSessionKind kind) {
            return new Request(sessionId, kind);
        }

        public Request withListenerKind(SleuthSpyDispatcher.ListenerKind listenerKind) {
            this.listenerKind = listenerKind != null ? listenerKind : SleuthSpyDispatcher.ListenerKind.OTHER;
            return this;
        }

        public Request withListener(SleuthAdviceListener listener) {
            this.listener = listener;
            return this;
        }

        public Request withCommandName(String commandName) {
            this.commandName = trimToNull(commandName);
            return this;
        }

        public Request withClassPattern(String classPattern) {
            this.classPattern = trimToNull(classPattern);
            return this;
        }

        public Request withMethodPattern(String methodPattern) {
            this.methodPattern = trimToNull(methodPattern);
            return this;
        }

        public Request withBackgroundJobId(String backgroundJobId) {
            this.backgroundJobId = trimToNull(backgroundJobId);
            return this;
        }

        public Request withDetails(String details) {
            this.details = trimToNull(details);
            return this;
        }

        public Request withOnClose(CloseHook onClose) {
            this.onClose = onClose;
            return this;
        }

        public Request withTarget(Class<?> clazz, ClassEnhancer enhancer) {
            this.targets.add(new Target(clazz, enhancer));
            return this;
        }

        public Request withTargets(List<Target> targets) {
            if (targets != null) {
                this.targets.addAll(targets);
            }
            return this;
        }

        public Request build() {
            validate();
            return this;
        }

        private void validate() {
            if (sessionId == null) {
                throw new IllegalArgumentException("sessionId is required");
            }
            if (listener == null) {
                throw new IllegalArgumentException("listener is required");
            }
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("at least one target is required");
            }
            for (Target target : targets) {
                if (target == null || target.clazz == null) {
                    throw new IllegalArgumentException("target class is required");
                }
                if (target.enhancer == null) {
                    throw new IllegalArgumentException("target enhancer is required");
                }
            }
        }

        private static String defaultCommandName(EnhancementSessionKind kind) {
            if (kind == null || kind == EnhancementSessionKind.OTHER) {
                return "enhancement";
            }
            return kind.name().toLowerCase(Locale.ROOT);
        }
    }

    public static final class Target {
        private final Class<?> clazz;
        private final ClassEnhancer enhancer;

        public Target(Class<?> clazz, ClassEnhancer enhancer) {
            this.clazz = clazz;
            this.enhancer = enhancer;
        }

        public Class<?> getClazz() {
            return clazz;
        }

        public ClassEnhancer getEnhancer() {
            return enhancer;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
