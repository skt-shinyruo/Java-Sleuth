package com.javasleuth.core.enhancement.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EnhancementSessionRegistry implements AutoCloseable {
    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();

    public EnhancementSessionHandle register(EnhancementSessionDescriptor descriptor, EnhancementSessionCloser closer) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor is required");
        }
        if (closer == null) {
            throw new IllegalArgumentException("closer is required");
        }
        Entry next = new Entry(descriptor, closer);
        Entry old = sessions.put(descriptor.getSessionId(), next);
        if (old != null) {
            old.closeInternal("replaced");
        }
        return next;
    }

    public boolean close(String sessionId, String reason) {
        CloseResult result = closeOne(sessionId, reason);
        return result.selected && !result.failed;
    }

    public int closeByClient(String clientId, String reason) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return 0;
        }
        int closed = 0;
        for (Entry entry : new ArrayList<Entry>(sessions.values())) {
            if (entry == null || entry.descriptor == null) {
                continue;
            }
            if (clientId.equals(entry.descriptor.getClientId())) {
                CloseResult result = closeEntry(entry, reason);
                if (result.selected && !result.failed) {
                    closed++;
                }
            }
        }
        return closed;
    }

    public EnhancementSessionCloseSummary closeAll(String reason) {
        List<Entry> entries = new ArrayList<Entry>(sessions.values());
        int closed = 0;
        int missing = 0;
        int failed = 0;
        Map<String, String> failures = new LinkedHashMap<String, String>();
        for (Entry entry : entries) {
            String id = entry != null ? entry.getSessionId() : null;
            CloseResult result = closeEntry(entry, reason);
            if (!result.selected) {
                missing++;
            } else if (result.failed) {
                failed++;
                failures.put(id, result.failureMessage);
            } else {
                closed++;
            }
        }
        return new EnhancementSessionCloseSummary(entries.size(), closed, missing, failed, failures);
    }

    public List<EnhancementSessionSnapshot> list() {
        List<Entry> entries = new ArrayList<Entry>(sessions.values());
        Collections.sort(entries, (a, b) -> Long.compare(a.descriptor.getCreatedAtMs(), b.descriptor.getCreatedAtMs()));
        List<EnhancementSessionSnapshot> out = new ArrayList<EnhancementSessionSnapshot>();
        for (Entry entry : entries) {
            out.add(new EnhancementSessionSnapshot(entry.descriptor));
        }
        return Collections.unmodifiableList(out);
    }

    public Map<EnhancementSessionKind, Integer> countByKind() {
        EnumMap<EnhancementSessionKind, Integer> counts = new EnumMap<EnhancementSessionKind, Integer>(EnhancementSessionKind.class);
        for (EnhancementSessionKind kind : EnhancementSessionKind.values()) {
            counts.put(kind, 0);
        }
        for (Entry entry : sessions.values()) {
            EnhancementSessionKind kind = entry != null && entry.descriptor != null
                ? entry.descriptor.getKind()
                : EnhancementSessionKind.OTHER;
            counts.put(kind, counts.get(kind) + 1);
        }
        return Collections.unmodifiableMap(counts);
    }

    public int size() {
        return sessions.size();
    }

    @Override
    public void close() {
        closeAll("close");
    }

    private CloseResult closeOne(String sessionId, String reason) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return CloseResult.missing();
        }
        Entry entry = sessions.remove(sessionId.trim());
        if (entry == null) {
            return CloseResult.missing();
        }
        return entry.closeInternal(reason);
    }

    private CloseResult closeEntry(Entry entry, String reason) {
        if (entry == null) {
            return CloseResult.missing();
        }
        if (!sessions.remove(entry.getSessionId(), entry)) {
            return CloseResult.missing();
        }
        return entry.closeInternal(reason);
    }

    private static final class CloseResult {
        private final boolean selected;
        private final boolean failed;
        private final String failureMessage;

        private CloseResult(boolean selected, boolean failed, String failureMessage) {
            this.selected = selected;
            this.failed = failed;
            this.failureMessage = failureMessage;
        }

        private static CloseResult closed() {
            return new CloseResult(true, false, null);
        }

        private static CloseResult failed(Throwable t) {
            String msg = t == null ? "unknown" : t.getClass().getName() + ": " + t.getMessage();
            return new CloseResult(true, true, msg);
        }

        private static CloseResult missing() {
            return new CloseResult(false, false, null);
        }
    }

    private final class Entry implements EnhancementSessionHandle {
        private final EnhancementSessionDescriptor descriptor;
        private final EnhancementSessionCloser closer;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Entry(EnhancementSessionDescriptor descriptor, EnhancementSessionCloser closer) {
            this.descriptor = descriptor;
            this.closer = closer;
        }

        @Override
        public String getSessionId() {
            return descriptor.getSessionId();
        }

        @Override
        public EnhancementSessionKind getKind() {
            return descriptor.getKind();
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close(String reason) {
            EnhancementSessionRegistry.this.closeEntry(this, reason);
        }

        private CloseResult closeInternal(String reason) {
            if (!closed.compareAndSet(false, true)) {
                return CloseResult.closed();
            }
            try {
                closer.close(reason);
                return CloseResult.closed();
            } catch (Throwable t) {
                return CloseResult.failed(t);
            }
        }
    }
}
