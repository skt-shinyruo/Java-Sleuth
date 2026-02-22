package com.javasleuth.foundation.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Small helper utilities for consistent executor shutdown behavior.
 *
 * <p>Design goals:</p>
 * <ul>
 *   <li>Keep it JDK-only (foundation).</li>
 *   <li>Prefer bounded waiting to avoid shutdown hang.</li>
 *   <li>Be best-effort and idempotent friendly.</li>
 * </ul>
 */
public final class SleuthExecutors {
    private SleuthExecutors() {}

    public static void shutdownAndAwait(ExecutorService executor, String name, long timeout, TimeUnit unit) {
        if (executor == null) {
            return;
        }
        try {
            executor.shutdown();
        } catch (Exception ignore) {
            return;
        }
        awaitOrForceShutdown(executor, name, timeout, unit);
    }

    public static void shutdownNowAndAwait(ExecutorService executor, String name, long timeout, TimeUnit unit) {
        if (executor == null) {
            return;
        }
        try {
            executor.shutdownNow();
        } catch (Exception ignore) {
            return;
        }
        awaitOrForceShutdown(executor, name, timeout, unit);
    }

    private static void awaitOrForceShutdown(ExecutorService executor, String name, long timeout, TimeUnit unit) {
        try {
            if (!executor.awaitTermination(timeout, unit)) {
                try {
                    executor.shutdownNow();
                } catch (Exception ignore) {
                    // ignore
                }
                // Best-effort second await; keep it bounded.
                try {
                    executor.awaitTermination(Math.min(5, Math.max(1, unit.toSeconds(timeout))), TimeUnit.SECONDS);
                } catch (Exception ignore) {
                    // ignore
                }
                if (name != null && !name.trim().isEmpty()) {
                    SleuthLogger.debug("Executor did not terminate in time (name=" + name + ")");
                }
            }
        } catch (InterruptedException e) {
            try {
                executor.shutdownNow();
            } catch (Exception ignore) {
                // ignore
            }
            Thread.currentThread().interrupt();
        } catch (Exception ignore) {
            // ignore
        }
    }
}
