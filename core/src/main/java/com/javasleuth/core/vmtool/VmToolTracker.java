package com.javasleuth.core.vmtool;

import com.javasleuth.core.util.RingBuffer;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core-side vmtool tracker store (instances captured from constructor-return events).
 *
 * <p>Design:</p>
 * <ul>
 *   <li>Per-track bounded cache (ring buffer) to keep memory stable</li>
 *   <li>WeakReference to avoid keeping application instances alive</li>
 *   <li>Best-effort, never throws on hot paths</li>
 * </ul>
 */
public final class VmToolTracker {

    public static final class TrackStats {
        private final String trackId;
        private final String baseClassName;
        private final long createdAtMs;
        private final int maxEntries;
        private final long capturedTotal;
        private final int cached;
        private final int alive;

        public TrackStats(
            String trackId,
            String baseClassName,
            long createdAtMs,
            int maxEntries,
            long capturedTotal,
            int cached,
            int alive
        ) {
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

        public TrackedInstanceInfo(
            long refId,
            String className,
            int identityHash,
            long capturedAtMs,
            String capturedThread,
            boolean alive
        ) {
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

        final Object lock = new Object();
        final RingBuffer<Long> order;
        final Map<Long, TrackedRef> refs = new HashMap<>();

        TrackSession(String id, String baseClassName, int maxEntries) {
            this.id = id;
            this.baseClassName = baseClassName != null ? baseClassName : "<unknown>";
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
                pruneUnlocked();
            }
        }

        private void pruneUnlocked() {
            List<Long> keep = order.snapshot();
            if (keep.isEmpty() || refs.size() <= keep.size()) {
                return;
            }
            HashSet<Long> keepSet = new HashSet<>(keep.size() * 2);
            for (Long id : keep) {
                if (id != null) {
                    keepSet.add(id);
                }
            }
            refs.keySet().removeIf(k -> k == null || !keepSet.contains(k));
        }

        TrackedRef get(long refId) {
            synchronized (lock) {
                return refs.get(refId);
            }
        }

        List<TrackedInstanceInfo> list(int limit, boolean aliveOnly) {
            int max = limit <= 0 ? 50 : Math.min(limit, 500);
            List<TrackedRef> snapshot;
            synchronized (lock) {
                snapshot = new ArrayList<>(refs.values());
            }
            snapshot.sort(Comparator.comparingLong(a -> a != null ? a.refId : 0L));

            List<TrackedInstanceInfo> out = new ArrayList<>();
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

    private final ConcurrentHashMap<String, TrackSession> sessions = new ConcurrentHashMap<>();

    public boolean registerTrack(String trackId, String baseClassName, int maxEntries) {
        if (trackId == null || trackId.trim().isEmpty()) {
            return false;
        }
        String id = trackId.trim();
        sessions.put(id, new TrackSession(id, baseClassName, maxEntries));
        return true;
    }

    public void unregisterTrack(String trackId) {
        if (trackId == null) {
            return;
        }
        sessions.remove(trackId.trim());
    }

    public void clearAll() {
        sessions.clear();
    }

    public void onConstructed(String trackId, Object instance) {
        if (trackId == null || trackId.trim().isEmpty() || instance == null) {
            return;
        }
        TrackSession s = sessions.get(trackId.trim());
        if (s == null) {
            return;
        }
        try {
            s.add(instance);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    public TrackStats getTrackStats(String trackId) {
        if (trackId == null) {
            return null;
        }
        TrackSession s = sessions.get(trackId.trim());
        return s != null ? s.stats() : null;
    }

    public Map<String, TrackStats> listTrackStats() {
        if (sessions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, TrackStats> out = new HashMap<>();
        for (Map.Entry<String, TrackSession> e : sessions.entrySet()) {
            TrackSession s = e.getValue();
            if (s == null) {
                continue;
            }
            out.put(e.getKey(), s.stats());
        }
        return out;
    }

    public List<TrackedInstanceInfo> listInstances(String trackId, int limit, boolean aliveOnly) {
        if (trackId == null) {
            return Collections.emptyList();
        }
        TrackSession s = sessions.get(trackId.trim());
        if (s == null) {
            return Collections.emptyList();
        }
        return s.list(limit, aliveOnly);
    }

    public Object getInstance(String trackId, long refId) {
        if (trackId == null) {
            return null;
        }
        TrackSession s = sessions.get(trackId.trim());
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
}
