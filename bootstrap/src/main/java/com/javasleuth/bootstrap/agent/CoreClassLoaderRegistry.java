package com.javasleuth.bootstrap.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bootstrap-visible ClassLoader registry for "attach lifecycle" boundary.
 *
 * <p>Goal: treat the isolated core/container ClassLoader as the lifecycle boundary for one attach.
 * When detach/shutdown happens, core/container should notify this registry so we can:
 * <ul>
 *   <li>close the URLClassLoader (best-effort; important on Windows to release JAR locks)</li>
 *   <li>clear strong references so the ClassLoader can be GC'ed</li>
 *   <li>act as a shared attach gate (SSOT) across classloader boundaries</li>
 * </ul>
 *
 * <p>Implementation constraints:
 * <ul>
 *   <li>JDK-only (bootstrap module)</li>
 *   <li>must not reference any core/container types (avoid reverse leaks)</li>
 * </ul>
 */
public final class CoreClassLoaderRegistry {
    private static final AtomicBoolean WARNED_NON_BOOTSTRAP = new AtomicBoolean(false);

    private CoreClassLoaderRegistry() {
    }

    /**
     * Best-effort: detect if this class is not loaded by BootstrapClassLoader.
     *
     * <p>If this happens, agent/core may see different copies of this class, and the registry won't
     * be a true SSOT. We still keep behavior functional in-process but emit a warning once.</p>
     */
    public static boolean isBootstrapLoaded() {
        return CoreClassLoaderRegistry.class.getClassLoader() == null;
    }

    public static boolean isRegistered() {
        warnIfNotBootstrapLoaded();
        return getRegistered() != null;
    }

    public static ClassLoader getRegistered() {
        warnIfNotBootstrapLoaded();
        return AgentLifecycle.getCommittedIsolatedClassLoaderBestEffort();
    }

    /**
     * Try to register the given ClassLoader as "currently attached".
     *
     * <p>Returns true when registration succeeds; false means there is already an active attach
     * (or a previous attach that was not fully released).</p>
     *
     * <p>Caller is responsible for closing the loader if registration fails.</p>
     */
    public static boolean tryRegister(ClassLoader loader) {
        warnIfNotBootstrapLoaded();
        if (loader == null) {
            return false;
        }

        long sessionId = AgentLifecycle.tryBeginAttach();
        if (sessionId == 0L) {
            return false;
        }

        boolean committed = AgentLifecycle.commitIsolatedClassLoader(sessionId, loader);
        if (!committed) {
            AgentLifecycle.failBestEffort(sessionId, loader);
            return false;
        }
        return true;
    }

    /**
     * Release the registered loader if (and only if) it matches the provided loader.
     *
     * <p>This method is idempotent and best-effort: it never throws.</p>
     */
    public static void onCoreShutdown(ClassLoader loader) {
        warnIfNotBootstrapLoaded();
        AgentLifecycle.detachBestEffort(loader);
    }

    /**
     * Rollback hook used when core/container fails to start after registration.
     */
    public static void releaseOnFailure(ClassLoader loader) {
        warnIfNotBootstrapLoaded();
        AgentLifecycle.detachBestEffort(loader);
    }

    private static void warnIfNotBootstrapLoaded() {
        if (isBootstrapLoaded()) {
            return;
        }
        String enabled = null;
        try {
            enabled = System.getProperty("sleuth.bootstrap.registry.warn.nonbootstrap", "true");
        } catch (Throwable ignore) {
            enabled = "true";
        }
        if (!"true".equalsIgnoreCase(enabled)) {
            return;
        }
        if (!WARNED_NON_BOOTSTRAP.compareAndSet(false, true)) {
            return;
        }
        try {
            System.err.println(
                "Java-Sleuth: CoreClassLoaderRegistry is NOT loaded by BootstrapClassLoader; attach gate may be degraded."
            );
        } catch (Throwable ignore) {
            // ignore
        }
    }
}
