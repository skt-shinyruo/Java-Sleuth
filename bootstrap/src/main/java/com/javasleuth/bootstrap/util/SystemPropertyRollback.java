package com.javasleuth.bootstrap.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统属性（System properties）回滚句柄。
 *
 * <p>用途：在 attach 过程中把 agentArgs 写入的 sysprop 作为“外部覆盖通道”使用，
 * 但在 detach/shutdown 时将其恢复为 attach 前的值，避免跨 attach 状态残留。</p>
 *
 * <p>约束：JDK-only（bootstrap 模块可见），不依赖 core/container 类型。</p>
 */
public final class SystemPropertyRollback {
    private static final SystemPropertyRollback NOOP = new SystemPropertyRollback(true);

    private final boolean noop;
    private final AtomicBoolean rolledBack = new AtomicBoolean(false);
    private final Map<String, String> oldValuesByKey = new LinkedHashMap<>();

    private SystemPropertyRollback(boolean noop) {
        this.noop = noop;
    }

    public SystemPropertyRollback() {
        this(false);
    }

    public static SystemPropertyRollback noop() {
        return NOOP;
    }

    /**
     * 记录某个 key 的旧值（仅首次记录生效）。
     *
     * <p>应当在执行 {@code System.setProperty(key, ...)} 之前调用。</p>
     */
    public void recordBeforeSet(String key) {
        if (noop) {
            return;
        }
        if (key == null) {
            return;
        }
        String k = key.trim();
        if (k.isEmpty()) {
            return;
        }
        synchronized (oldValuesByKey) {
            if (oldValuesByKey.containsKey(k)) {
                return;
            }
            oldValuesByKey.put(k, System.getProperty(k));
        }
    }

    /**
     * Best-effort 回滚。多次调用只会生效一次。
     *
     * <p>若旧值为 null，则会 clear 对应 sysprop。</p>
     */
    public void rollbackBestEffort() {
        if (noop) {
            return;
        }
        if (!rolledBack.compareAndSet(false, true)) {
            return;
        }

        Map<String, String> snapshot;
        synchronized (oldValuesByKey) {
            snapshot = new LinkedHashMap<>(oldValuesByKey);
        }

        for (Map.Entry<String, String> e : snapshot.entrySet()) {
            if (e == null) {
                continue;
            }
            String key = e.getKey();
            String old = e.getValue();
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            try {
                if (old == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, old);
                }
            } catch (Throwable ignore) {
                // best-effort
            }
        }
    }

    /**
     * 仅用于调试与测试：查看本次回滚句柄触达的 key（不可修改）。
     */
    public Map<String, String> snapshotTouchedKeys() {
        if (noop) {
            return Collections.emptyMap();
        }
        synchronized (oldValuesByKey) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(oldValuesByKey));
        }
    }
}

