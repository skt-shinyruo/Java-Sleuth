package com.javasleuth.vmtool;

import com.javasleuth.enhancement.InstanceTrackEnhancer;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.monitor.VmToolInterceptor;
import com.javasleuth.util.LoadedClassResolver;
import com.javasleuth.util.StringUtils;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * vmtool track 会话管理（用于可回滚地添加/移除构造器追踪增强）。
 */
public final class VmToolSessionRegistry {
    public static final class EnhancedClass {
        private final Class<?> clazz;
        private final InstanceTrackEnhancer enhancer;
        private final String className;
        private final int loaderId;

        private EnhancedClass(Class<?> clazz, InstanceTrackEnhancer enhancer) {
            this.clazz = clazz;
            this.enhancer = enhancer;
            this.className = clazz != null ? clazz.getName() : "<null>";
            this.loaderId = LoadedClassResolver.loaderId(clazz != null ? clazz.getClassLoader() : null);
        }

        public Class<?> getClazz() { return clazz; }
        public InstanceTrackEnhancer getEnhancer() { return enhancer; }
        public String getClassName() { return className; }
        public int getLoaderId() { return loaderId; }
    }

    public static final class TrackSession {
        private final String id;
        private final String baseClassName;
        private final int baseLoaderId;
        private final boolean includeSubclasses;
        private final int maxEntries;
        private final long createdAtMs;
        private final List<EnhancedClass> enhancedClasses;

        private TrackSession(String id,
                             String baseClassName,
                             int baseLoaderId,
                             boolean includeSubclasses,
                             int maxEntries,
                             List<EnhancedClass> enhancedClasses) {
            this.id = id;
            this.baseClassName = baseClassName;
            this.baseLoaderId = baseLoaderId;
            this.includeSubclasses = includeSubclasses;
            this.maxEntries = maxEntries;
            this.createdAtMs = System.currentTimeMillis();
            this.enhancedClasses = enhancedClasses != null ? enhancedClasses : Collections.emptyList();
        }

        public String getId() { return id; }
        public String getBaseClassName() { return baseClassName; }
        public int getBaseLoaderId() { return baseLoaderId; }
        public boolean isIncludeSubclasses() { return includeSubclasses; }
        public int getMaxEntries() { return maxEntries; }
        public long getCreatedAtMs() { return createdAtMs; }
        public List<EnhancedClass> getEnhancedClasses() { return enhancedClasses; }
    }

    public static final class StartResult {
        private final boolean ok;
        private final String message;
        private final TrackSession session;

        private StartResult(boolean ok, String message, TrackSession session) {
            this.ok = ok;
            this.message = message;
            this.session = session;
        }

        public static StartResult ok(TrackSession session, String message) {
            return new StartResult(true, message, session);
        }

        public static StartResult failed(String message) {
            return new StartResult(false, message, null);
        }

        public boolean isOk() { return ok; }
        public String getMessage() { return message; }
        public TrackSession getSession() { return session; }
    }

    public static final class StopResult {
        private final boolean ok;
        private final String message;

        private StopResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        public static StopResult ok(String message) {
            return new StopResult(true, message);
        }

        public static StopResult failed(String message) {
            return new StopResult(false, message);
        }

        public boolean isOk() { return ok; }
        public String getMessage() { return message; }
    }

    private final ConcurrentHashMap<String, TrackSession> sessions = new ConcurrentHashMap<>();

    public VmToolSessionRegistry() {}

    public Map<String, TrackSession> listSessions() {
        return Collections.unmodifiableMap(sessions);
    }

    public TrackSession getSession(String trackId) {
        if (trackId == null) {
            return null;
        }
        return sessions.get(trackId.trim());
    }

    public StartResult startTrack(Instrumentation instrumentation,
                                 SleuthClassFileTransformer transformer,
                                 String classPattern,
                                 Integer loaderId,
                                 boolean allowFirstWhenAmbiguous,
                                 boolean includeSubclasses,
                                 int maxEntries,
                                 int classLimit) {
        if (instrumentation == null || transformer == null) {
            return StartResult.failed("Instrumentation/transformer not available.");
        }
        if (classPattern == null || classPattern.trim().isEmpty()) {
            return StartResult.failed("Usage: vmtool track <class-pattern> ...");
        }

        int max = maxEntries <= 0 ? 500 : Math.min(maxEntries, 10_000);
        int limit = classLimit <= 0 ? 50 : Math.min(classLimit, 500);

        LoadedClassResolver.Candidate resolved;
        try {
            resolved = LoadedClassResolver.resolveSingle(instrumentation, classPattern.trim(), loaderId, true, 200, allowFirstWhenAmbiguous);
        } catch (LoadedClassResolver.ResolutionException e) {
            return StartResult.failed(e.getMessage() +
                "\nCandidates:\n" + LoadedClassResolver.formatCandidates(e.getCandidates(), 10) +
                "\nHint: use --loader <loaderId> (e.g. --loader 0x1234 or --loader bootstrap)");
        }

        Class<?> base = resolved.getClazz();
        if (base == null) {
            return StartResult.failed("Target class not found in loaded classes: " + resolved.getClassName());
        }
        if (!instrumentation.isModifiableClass(base)) {
            return StartResult.failed("Target class is not modifiable: " + base.getName());
        }

        String id = "t-" + UUID.randomUUID().toString().replace("-", "");
        String baseClassName = base.getName();
        int baseLoaderId = resolved.getLoaderId();

        // Prepare class list
        List<Class<?>> targets = new ArrayList<>();
        if (!includeSubclasses) {
            targets.add(base);
        } else {
            Class<?>[] loaded = instrumentation.getAllLoadedClasses();
            for (Class<?> c : loaded) {
                if (c == null) {
                    continue;
                }
                if (targets.size() >= limit) {
                    break;
                }
                try {
                    if (c.isInterface()) {
                        continue;
                    }
                    if (!base.isAssignableFrom(c)) {
                        continue;
                    }
                } catch (Throwable ignore) {
                    continue;
                }
                if (!instrumentation.isModifiableClass(c)) {
                    continue;
                }
                targets.add(c);
            }
            // Ensure base class included.
            if (!targets.contains(base)) {
                targets.add(0, base);
            }
        }

        if (targets.isEmpty()) {
            return StartResult.failed("No modifiable classes selected for tracking: " + baseClassName);
        }

        // Register interceptor session first.
        VmToolInterceptor.registerTrack(id, baseClassName, max);

        List<EnhancedClass> enhanced = new ArrayList<>();
        int retransformed = 0;
        int failed = 0;
        for (Class<?> c : targets) {
            if (c == null) {
                continue;
            }
            if (!instrumentation.isModifiableClass(c)) {
                failed++;
                continue;
            }
            InstanceTrackEnhancer enhancer = new InstanceTrackEnhancer(id, c.getName());
            try {
                transformer.addEnhancer(c, enhancer);
                try {
                    instrumentation.retransformClasses(c);
                    retransformed++;
                } catch (Exception ex) {
                    // rollback this enhancer
                    transformer.removeEnhancer(c, enhancer);
                    failed++;
                    continue;
                }
                enhanced.add(new EnhancedClass(c, enhancer));
            } catch (Throwable t) {
                try {
                    transformer.removeEnhancer(c, enhancer);
                } catch (Throwable ignore) {
                    // ignore
                }
                failed++;
            }
        }

        if (enhanced.isEmpty()) {
            VmToolInterceptor.unregisterTrack(id);
            return StartResult.failed("Failed to instrument any target classes. base=" + baseClassName);
        }

        TrackSession session = new TrackSession(id, baseClassName, baseLoaderId, includeSubclasses, max, Collections.unmodifiableList(enhanced));
        sessions.put(id, session);

        String msg = "vmtool track started. id=" + id +
            ", base=" + baseClassName +
            ", loaderId=" + LoadedClassResolver.formatLoaderId(baseLoaderId) +
            ", includeSubclasses=" + includeSubclasses +
            ", maxEntries=" + max +
            ", instrumented=" + enhanced.size() +
            ", retransformed=" + retransformed +
            ", failed=" + failed;
        return StartResult.ok(session, msg);
    }

    public StopResult stopTrack(Instrumentation instrumentation, SleuthClassFileTransformer transformer, String trackId) {
        if (trackId == null || trackId.trim().isEmpty()) {
            return StopResult.failed("Usage: vmtool stop <track-id>");
        }
        String id = trackId.trim();
        TrackSession session = sessions.remove(id);
        if (session == null) {
            VmToolInterceptor.unregisterTrack(id);
            return StopResult.failed("Track not found: " + id);
        }

        int removed = 0;
        int retransformed = 0;
        int skipped = 0;
        for (EnhancedClass ec : session.getEnhancedClasses()) {
            if (ec == null || ec.getClazz() == null || ec.getEnhancer() == null) {
                skipped++;
                continue;
            }
            try {
                transformer.removeEnhancer(ec.getClazz(), ec.getEnhancer());
                removed++;
            } catch (Throwable ignore) {
                skipped++;
                continue;
            }
            try {
                if (instrumentation != null && instrumentation.isModifiableClass(ec.getClazz())) {
                    instrumentation.retransformClasses(ec.getClazz());
                    retransformed++;
                } else {
                    skipped++;
                }
            } catch (Throwable ignore) {
                skipped++;
            }
        }

        VmToolInterceptor.unregisterTrack(id);
        return StopResult.ok("vmtool track stopped. id=" + id +
            ", removedEnhancers=" + removed +
            ", retransformed=" + retransformed +
            ", skipped=" + skipped);
    }

    public StopResult stopAll(Instrumentation instrumentation, SleuthClassFileTransformer transformer, String reason) {
        int total = sessions.size();
        int stopped = 0;
        List<String> ids = new ArrayList<>(sessions.keySet());
        for (String id : ids) {
            StopResult r = stopTrack(instrumentation, transformer, id);
            if (r != null && r.isOk()) {
                stopped++;
            }
        }
        VmToolInterceptor.clearAll();
        String why = StringUtils.isBlank(reason) ? "" : (" reason=" + reason.trim());
        return StopResult.ok("vmtool tracks cleared. total=" + total + ", stopped=" + stopped + why);
    }

    /**
     * Best-effort shutdown hook for detach/re-attach/tests.
     *
     * <p>Stops all tracks (attempts to remove enhancers + retransform) and clears local session index.</p>
     */
    public void shutdown(Instrumentation instrumentation, SleuthClassFileTransformer transformer, String reason) {
        try {
            stopAll(instrumentation, transformer, reason);
        } catch (Exception ignore) {
            // best-effort
        } finally {
            try {
                sessions.clear();
            } catch (Exception ignore) {
                // best-effort
            }
        }
    }
}
