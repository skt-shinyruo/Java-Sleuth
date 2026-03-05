package com.javasleuth.core.command;

import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.foundation.config.ProductionConfig;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

/**
 * Regression guard: {@link CommandProcessor} standalone usage should not leak background threads.
 *
 * <p>When created via the default factory wiring, the processor owns attach-scope services such as
 * {@code AuditLogger} and {@code AuthenticationManager}. These services start background threads
 * immediately and must be released on shutdown.
 */
public class CommandProcessorOwnedResourcesCloseTest {
    private static final long SETTLE_TIMEOUT_MS = 5000;
    private static final long POLL_INTERVAL_MS = 50;

    @Test
    public void shutdownGracefully_closesOwnedResources_threadsExit() throws Exception {
        Set<Long> baselineAuditThreadIds = snapshotThreadIdsByName("sleuth-audit-logger");
        Set<Long> baselineCleanupThreadIds = snapshotThreadIdsByName("sleuth-session-cleanup");

        CommandProcessor processor = new CommandProcessor(
            fakeInstrumentation(),
            new SleuthClassFileTransformer(ProductionConfig.createDefault())
        );

        try {
            Thread auditThread = awaitNewThreadByName("sleuth-audit-logger", baselineAuditThreadIds, SETTLE_TIMEOUT_MS);
            Thread cleanupThread = awaitNewThreadByName("sleuth-session-cleanup", baselineCleanupThreadIds, SETTLE_TIMEOUT_MS);

            processor.shutdownGracefully(1);

            assertEventuallyThreadStops("sleuth-audit-logger", auditThread, SETTLE_TIMEOUT_MS);
            assertEventuallyThreadStops("sleuth-session-cleanup", cleanupThread, SETTLE_TIMEOUT_MS);
        } finally {
            // Best-effort: if test fails mid-way, still try to stop the processor.
            try {
                processor.shutdownGracefully(1);
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    private static Set<Long> snapshotThreadIdsByName(String threadName) {
        Set<Long> ids = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t == null) {
                continue;
            }
            if (threadName.equals(t.getName())) {
                ids.add(t.getId());
            }
        }
        return ids;
    }

    private static Thread awaitNewThreadByName(String threadName, Set<Long> excludeIds, long timeoutMs)
        throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadlineNanos) {
            Thread t = findThreadByName(threadName, excludeIds);
            if (t != null) {
                return t;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        Thread t = findThreadByName(threadName, excludeIds);
        if (t != null) {
            return t;
        }
        Assert.fail("Expected background thread '" + threadName + "' to start within " + timeoutMs + "ms");
        return null;
    }

    private static Thread findThreadByName(String threadName, Set<Long> excludeIds) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t == null) {
                continue;
            }
            if (!threadName.equals(t.getName())) {
                continue;
            }
            if (excludeIds != null && excludeIds.contains(t.getId())) {
                continue;
            }
            if (!t.isAlive()) {
                continue;
            }
            return t;
        }
        return null;
    }

    private static void assertEventuallyThreadStops(String threadName, Thread t, long timeoutMs) throws InterruptedException {
        if (t == null) {
            return;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadlineNanos) {
            if (!t.isAlive()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        Assert.fail("Thread '" + threadName + "' is still alive after shutdown (waited ~" + timeoutMs + "ms)");
    }

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getAllLoadedClasses".equals(name)) {
                    return new Class<?>[0];
                }
                if ("isModifiableClass".equals(name)) {
                    return true;
                }
                if ("removeTransformer".equals(name)) {
                    return true;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == Void.TYPE) {
                    return null;
                }
                if (returnType == Boolean.TYPE) {
                    return false;
                }
                if (returnType == Integer.TYPE) {
                    return 0;
                }
                if (returnType == Long.TYPE) {
                    return 0L;
                }
                if (returnType.isArray()) {
                    return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                }
                return null;
            }
        );
    }
}

