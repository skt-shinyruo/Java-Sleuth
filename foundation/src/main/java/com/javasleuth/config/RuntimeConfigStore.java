package com.javasleuth.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stores runtime config overrides and keeps a lightweight audit trail.
 */
public final class RuntimeConfigStore {
    private static final int DEFAULT_RECENT_CHANGES_LIMIT = 20;

    private final ConcurrentHashMap<String, String> overrides = new ConcurrentHashMap<>();
    private final SensitiveKeyMasker masker;
    private final AtomicLong sequence = new AtomicLong(0L);

    private final Deque<ConfigChange> recentChanges = new ArrayDeque<>();
    private final int recentLimit;

    public RuntimeConfigStore(SensitiveKeyMasker masker) {
        this(masker, DEFAULT_RECENT_CHANGES_LIMIT);
    }

    public RuntimeConfigStore(SensitiveKeyMasker masker, int recentLimit) {
        this.masker = masker != null ? masker : new SensitiveKeyMasker();
        this.recentLimit = recentLimit > 0 ? recentLimit : DEFAULT_RECENT_CHANGES_LIMIT;
    }

    public String get(String key) {
        if (key == null) {
            return null;
        }
        return overrides.get(key);
    }

    public int size() {
        return overrides.size();
    }

    public boolean isEmpty() {
        return overrides.isEmpty();
    }

    public Map<String, String> snapshot() {
        return new HashMap<>(overrides);
    }

    public void set(String key, String value, ConfigUpdateSource source) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        if (value == null) {
            return;
        }
        String old = overrides.put(key, value);
        recordChange(key, old, value, source);
    }

    public void remove(String key, ConfigUpdateSource source) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        String old = overrides.remove(key);
        if (old != null) {
            recordChange(key, old, null, source);
        }
    }

    public void clear(ConfigUpdateSource source) {
        int oldSize = overrides.size();
        if (oldSize <= 0) {
            return;
        }
        overrides.clear();
        recordChange("*", "count=" + oldSize, "count=0", source);
    }

    public List<ConfigChange> getRecentChanges(int limit) {
        int n = limit > 0 ? limit : recentLimit;
        synchronized (recentChanges) {
            if (recentChanges.isEmpty()) {
                return Collections.emptyList();
            }
            List<ConfigChange> list = new ArrayList<>(recentChanges);
            if (list.size() <= n) {
                return list;
            }
            return new ArrayList<>(list.subList(list.size() - n, list.size()));
        }
    }

    private void recordChange(String key, String oldValue, String newValue, ConfigUpdateSource source) {
        long seq = sequence.incrementAndGet();
        String oldSummary = masker.mask(key, oldValue);
        String newSummary = masker.mask(key, newValue);
        ConfigUpdateSource effectiveSource = source != null ? source : ConfigUpdateSource.UNKNOWN;

        ConfigChange change = new ConfigChange(
            seq,
            key,
            oldSummary,
            newSummary,
            effectiveSource,
            System.currentTimeMillis()
        );

        synchronized (recentChanges) {
            recentChanges.addLast(change);
            while (recentChanges.size() > recentLimit) {
                recentChanges.removeFirst();
            }
        }
    }
}

