package com.javasleuth.bootstrap.monitor;

import com.javasleuth.bootstrap.util.RingBuffer;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * vmtool 实例追踪拦截器（简化版）。
 *
 * <p>说明：</p>
 * <ul>
 *   <li>通过构造器插桩在对象构造完成后回调 {@link #onConstructed(String, Object)}。</li>
 *   <li>使用弱引用 + 有界缓存，避免对业务对象形成强引用导致内存泄漏。</li>
 *   <li>仅能追踪“启用 track 后新创建”的实例（不等价于遍历全堆存活对象）。</li>
 * </ul>
 */
public final class VmToolInterceptor {
    public static final class TrackStats {
        private final String trackId;
        private final String baseClassName;
        private final long createdAtMs;
        private final int maxEntries;
        private final long capturedTotal;
        private final int cached;
        private final int alive;

        private TrackStats(String trackId,
                           String baseClassName,
                           long createdAtMs,
                           int maxEntries,
                           long capturedTotal,
                           int cached,
                           int alive) {
            this.trackId = trackId;
            this.baseClassName = baseClassName;
            this.createdAtMs = createdAtMs;
            this.maxEntries = maxEntries;
            this.capturedTotal = capturedTotal;
            this.cached = cached;
            this.alive = alive;
        }

        public String getTrackId() { return trackId; }
        public String getBaseClassName() { return baseClassName; }
        public long getCreatedAtMs() { return createdAtMs; }
        public int getMaxEntries() { return maxEntries; }
        public long getCapturedTotal() { return capturedTotal; }
        public int getCached() { return cached; }
        public int getAlive() { return alive; }
    }

    public static final class TrackedInstanceInfo {
        private final long refId;
        private final String className;
        private final int identityHash;
        private final long capturedAtMs;
        private final String capturedThread;
        private final boolean alive;

        private TrackedInstanceInfo(long refId,
                                    String className,
                                    int identityHash,
                                    long capturedAtMs,
                                    String capturedThread,
                                    boolean alive) {
            this.refId = refId;
            this.className = className;
            this.identityHash = identityHash;
            this.capturedAtMs = capturedAtMs;
            this.capturedThread = capturedThread;
            this.alive = alive;
        }

        public long getRefId() { return refId; }
        public String getClassName() { return className; }
        public int getIdentityHash() { return identityHash; }
        public long getCapturedAtMs() { return capturedAtMs; }
        public String getCapturedThread() { return capturedThread; }
        public boolean isAlive() { return alive; }
    }

    private static final class TrackedRef {
        final long refId;
        final String className;
        final int identityHash;
        final long capturedAtMs;
        final String capturedThread;
        final WeakReference<Object> ref;

        TrackedRef(long refId, Object instance) {
            this.refId = refId;
            this.className = instance != null ? instance.getClass().getName() : "<null>";
            this.identityHash = instance != null ? System.identityHashCode(instance) : 0;
            this.capturedAtMs = System.currentTimeMillis();
            String t = null;
            try {
                Thread cur = Thread.currentThread();
                t = cur != null ? cur.getName() : null;
            } catch (Throwable ignore) {
                t = null;
            }
            this.capturedThread = t;
            this.ref = new WeakReference<>(instance);
        }
    }

    private static final class TrackSession {
        final String id;
        final String baseClassName;
        final long createdAtMs;
        final int maxEntries;
        final AtomicLong refSeq = new AtomicLong(0);
        final AtomicLong capturedTotal = new AtomicLong(0);

        // Bounded insertion order (refId)
        final RingBuffer<Long> order;
        final Object lock = new Object();
        final Map<Long, TrackedRef> refs = new HashMap<>();

        TrackSession(String id, String baseClassName, int maxEntries) {
            this.id = id;
            this.baseClassName = baseClassName;
            this.createdAtMs = System.currentTimeMillis();
            this.maxEntries = Math.max(1, maxEntries);
            this.order = new RingBuffer<>(this.maxEntries);
        }

        void add(Object instance) {
            if (instance == null) {
                return;
            }
            long refId = refSeq.incrementAndGet();
            TrackedRef tr = new TrackedRef(refId, instance);
            capturedTotal.incrementAndGet();

            synchronized (lock) {
                refs.put(refId, tr);
                order.add(refId);
                // Best-effort eviction: remove refs that are no longer in ring buffer snapshot.
                // RingBuffer 本身没有“移除最旧”回调，这里用 snapshot 做有界对齐。
                List<Long> keep = order.snapshot();
                if (refs.size() > keep.size()) {
                    // Build keep set for O(n) prune (n <= maxEntries).
                    Map<Long, Boolean> keepSet = new HashMap<>(keep.size() * 2);
                    for (Long id : keep) {
                        if (id != null) {
                            keepSet.put(id, Boolean.TRUE);
                        }
                    }
                    refs.keySet().removeIf(k -> k == null || !keepSet.containsKey(k));
                }
            }
        }

        TrackedRef get(long refId) {
            synchronized (lock) {
                return refs.get(refId);
            }
        }

        List<TrackedInstanceInfo> list(int limit, boolean aliveOnly) {
            int max = limit <= 0 ? 50 : Math.min(limit, 500);
            List<TrackedInstanceInfo> out = new ArrayList<>();
            List<TrackedRef> snapshot;
            synchronized (lock) {
                snapshot = new ArrayList<>(refs.values());
            }
            snapshot.sort(Comparator.comparingLong(a -> a != null ? a.refId : 0L));
            for (TrackedRef tr : snapshot) {
                if (tr == null) {
                    continue;
                }
                boolean alive = tr.ref != null && tr.ref.get() != null;
                if (aliveOnly && !alive) {
                    continue;
                }
                out.add(new TrackedInstanceInfo(tr.refId, tr.className, tr.identityHash, tr.capturedAtMs, tr.capturedThread, alive));
                if (out.size() >= max) {
                    break;
                }
            }
            return out;
        }

        TrackStats stats() {
            int cached;
            int alive;
            long total = capturedTotal.get();
            synchronized (lock) {
                cached = refs.size();
                int nAlive = 0;
                for (TrackedRef tr : refs.values()) {
                    if (tr != null && tr.ref != null && tr.ref.get() != null) {
                        nAlive++;
                    }
                }
                alive = nAlive;
            }
            return new TrackStats(id, baseClassName, createdAtMs, maxEntries, total, cached, alive);
        }
    }

    private static final ConcurrentHashMap<String, TrackSession> SESSIONS = new ConcurrentHashMap<>();

    private VmToolInterceptor() {}

    public static boolean registerTrack(String trackId, String baseClassName, int maxEntries) {
        if (trackId == null || trackId.trim().isEmpty()) {
            return false;
        }
        String id = trackId.trim();
        SESSIONS.put(id, new TrackSession(id, baseClassName != null ? baseClassName : "<unknown>", maxEntries));
        return true;
    }

    public static void unregisterTrack(String trackId) {
        if (trackId == null) {
            return;
        }
        SESSIONS.remove(trackId.trim());
    }

    public static void clearAll() {
        SESSIONS.clear();
    }

    /**
     * 由字节码增强调用，必须做到“绝不抛异常”。
     */
    public static void onConstructed(String trackId, Object instance) {
        if (trackId == null || trackId.trim().isEmpty() || instance == null) {
            return;
        }
        TrackSession s = SESSIONS.get(trackId.trim());
        if (s == null) {
            return;
        }
        try {
            s.add(instance);
        } catch (Throwable ignore) {
            // Swallow: never break business constructor.
        }
    }

    public static TrackStats getTrackStats(String trackId) {
        if (trackId == null) {
            return null;
        }
        TrackSession s = SESSIONS.get(trackId.trim());
        return s != null ? s.stats() : null;
    }

    public static Map<String, TrackStats> listTrackStats() {
        if (SESSIONS.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, TrackStats> out = new HashMap<>();
        for (Map.Entry<String, TrackSession> e : SESSIONS.entrySet()) {
            TrackSession s = e.getValue();
            if (s == null) {
                continue;
            }
            out.put(e.getKey(), s.stats());
        }
        return out;
    }

    public static List<TrackedInstanceInfo> listInstances(String trackId, int limit, boolean aliveOnly) {
        if (trackId == null) {
            return Collections.emptyList();
        }
        TrackSession s = SESSIONS.get(trackId.trim());
        if (s == null) {
            return Collections.emptyList();
        }
        return s.list(limit, aliveOnly);
    }

    public static Object getInstance(String trackId, long refId) {
        if (trackId == null) {
            return null;
        }
        TrackSession s = SESSIONS.get(trackId.trim());
        if (s == null) {
            return null;
        }
        TrackedRef tr = s.get(refId);
        if (tr == null || tr.ref == null) {
            return null;
        }
        try {
            return tr.ref.get();
        } catch (Throwable ignore) {
            return null;
        }
    }

    public static String formatIdentity(int identityHash) {
        return "0x" + Integer.toHexString(identityHash);
    }

    public static String formatMillis(long ms) {
        if (ms <= 0) {
            return "0";
        }
        if (ms < 1000) {
            return ms + "ms";
        }
        long s = ms / 1000;
        if (s < 60) {
            return s + "s";
        }
        long m = s / 60;
        if (m < 60) {
            return m + "m";
        }
        long h = m / 60;
        return h + "h";
    }

    public static String safeLower(String s) {
        if (s == null) {
            return null;
        }
        return s.toLowerCase(Locale.ROOT);
    }
}
