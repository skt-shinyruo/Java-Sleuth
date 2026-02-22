package com.javasleuth.foundation.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified thread factory for Java-Sleuth.
 *
 * <p>Goals:</p>
 * <ul>
 *   <li>Consistent naming and daemon policy.</li>
 *   <li>Low-friction adoption (JDK-only).</li>
 *   <li>Best-effort uncaught exception logging for diagnostics.</li>
 * </ul>
 */
public final class SleuthThreadFactory implements ThreadFactory {
    private final String namePrefix;
    private final boolean daemon;
    private final int priority;
    private final boolean includeSequence;
    private final AtomicLong seq = new AtomicLong(0);

    private SleuthThreadFactory(String namePrefix, boolean daemon, int priority, boolean includeSequence) {
        String p = namePrefix == null ? "" : namePrefix.trim();
        this.namePrefix = p.isEmpty() ? "sleuth-thread" : p;
        this.daemon = daemon;
        this.priority = priority;
        this.includeSequence = includeSequence;
    }

    public static SleuthThreadFactory daemon(String namePrefix) {
        return new SleuthThreadFactory(namePrefix, true, Thread.NORM_PRIORITY, true);
    }

    public static SleuthThreadFactory daemon(String namePrefix, int priority) {
        return new SleuthThreadFactory(namePrefix, true, priority, true);
    }

    /**
     * Create a daemon factory that keeps the first thread name exactly the provided name.
     * If the executor recreates the thread, a numeric suffix will be appended to avoid collisions.
     */
    public static SleuthThreadFactory daemonFixed(String name) {
        return new SleuthThreadFactory(name, true, Thread.NORM_PRIORITY, false);
    }

    public static SleuthThreadFactory daemonFixed(String name, int priority) {
        return new SleuthThreadFactory(name, true, priority, false);
    }

    @Override
    public Thread newThread(Runnable r) {
        long n = seq.incrementAndGet();
        String name = includeSequence ? (namePrefix + "-" + n) : (n == 1 ? namePrefix : (namePrefix + "-" + n));
        Thread t = new Thread(r, name);
        t.setDaemon(daemon);
        try {
            t.setPriority(priority);
        } catch (Exception ignore) {
            // ignore
        }
        try {
            t.setUncaughtExceptionHandler((th, ex) -> {
                try {
                    SleuthLogger.warn("Uncaught exception in thread " + th.getName() + ": " + ex.getMessage(), ex);
                } catch (Exception ignore) {
                    // ignore
                }
            });
        } catch (Exception ignore) {
            // ignore
        }
        return t;
    }
}
