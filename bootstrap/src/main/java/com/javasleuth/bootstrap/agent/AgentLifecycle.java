package com.javasleuth.bootstrap.agent;

import com.javasleuth.bootstrap.util.AgentArgsApplier;
import com.javasleuth.bootstrap.util.SystemPropertyRollback;
import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bootstrap-visible attach lifecycle SSOT (Single Source Of Truth).
 *
 * <p>Design goals:
 * <ul>
 *   <li>Single authoritative "attached/attaching" state within one JVM</li>
 *   <li>No global side effects without owning the attach token</li>
 *   <li>Centralized best-effort cleanup for detach → re-attach</li>
 *   <li>JDK-only (bootstrap module), safe to be invoked by the thin agent via reflection</li>
 * </ul>
 */
public final class AgentLifecycle {
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong(1L);
    private static final AtomicReference<AttachSession> CURRENT = new AtomicReference<>();

    private AgentLifecycle() {}

    /**
     * Try to begin a new attach session.
     *
     * @return a non-zero session id when begin succeeds; 0 means "already attached/attaching".
     */
    public static long tryBeginAttach() {
        long id = NEXT_SESSION_ID.getAndIncrement();
        AttachSession session = new AttachSession(id);
        return CURRENT.compareAndSet(null, session) ? id : 0L;
    }

    /**
     * Apply agentArgs to System properties and register the rollback handle in the current session.
     *
     * <p>This method is token-guarded: callers must pass the session id returned by {@link #tryBeginAttach()}.
     *
     * @return true if applied (or already applied) for this session; false if session id mismatched or no session exists.
     */
    public static boolean applyAgentArgsIfAbsent(long sessionId, String agentArgs) {
        AttachSession session = CURRENT.get();
        if (session == null || session.sessionId != sessionId) {
            return false;
        }
        session.applyAgentArgsIfAbsent(agentArgs);
        return true;
    }

    /**
     * Commit the isolated ClassLoader for this attach session.
     *
     * <p>This binds the lifecycle boundary to the provided loader, which is later used by
     * {@link #detachBestEffort(ClassLoader)} to ensure we only detach the correct session.</p>
     *
     * @return true if committed successfully (or already committed with the same loader); false otherwise.
     */
    public static boolean commitIsolatedClassLoader(long sessionId, ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        AttachSession session = CURRENT.get();
        if (session == null || session.sessionId != sessionId) {
            return false;
        }
        return session.commitIsolatedClassLoader(loader);
    }

    /**
     * Best-effort failure rollback for attach startup failures.
     *
     * <p>It clears the session gate (allowing re-attach), rolls back sysprops if they were applied,
     * and closes the isolated ClassLoader (best-effort).</p>
     */
    public static void failBestEffort(long sessionId, ClassLoader loaderOrNull) {
        AttachSession session = CURRENT.get();
        if (session == null) {
            closeBestEffort(loaderOrNull);
            return;
        }
        if (session.sessionId != sessionId) {
            closeBestEffort(loaderOrNull);
            return;
        }
        if (!session.tryStartCleanup()) {
            closeBestEffort(loaderOrNull);
            return;
        }
        try {
            session.cleanupBestEffort(loaderOrNull);
        } finally {
            CURRENT.compareAndSet(session, null);
        }
    }

    /**
     * Best-effort detach/shutdown hook invoked by the isolated runtime.
     *
     * <p>This only detaches when the provided loader matches the committed isolated loader.
     * This prevents accidentally detaching another active session.</p>
     */
    public static void detachBestEffort(ClassLoader selfClassLoader) {
        if (selfClassLoader == null) {
            return;
        }
        AttachSession session = CURRENT.get();
        if (session == null) {
            return;
        }
        if (!session.isCommittedLoader(selfClassLoader)) {
            return;
        }
        if (!session.tryStartCleanup()) {
            return;
        }
        try {
            session.cleanupBestEffort(null);
        } finally {
            CURRENT.compareAndSet(session, null);
        }
    }

    static ClassLoader getCommittedIsolatedClassLoaderBestEffort() {
        AttachSession session = CURRENT.get();
        return session != null ? session.committedLoader : null;
    }

    private static void closeBestEffort(ClassLoader loader) {
        if (!(loader instanceof Closeable)) {
            return;
        }
        try {
            ((Closeable) loader).close();
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private static final class AttachSession {
        private final long sessionId;
        private final AtomicBoolean cleanupStarted = new AtomicBoolean(false);
        private volatile SystemPropertyRollback rollback;
        private volatile ClassLoader committedLoader;

        private AttachSession(long sessionId) {
            this.sessionId = sessionId;
        }

        private void applyAgentArgsIfAbsent(String agentArgs) {
            if (rollback != null) {
                return;
            }
            synchronized (this) {
                if (rollback != null) {
                    return;
                }
                SystemPropertyRollback r = AgentArgsApplier.applyToSystemPropertiesWithRollback(agentArgs);
                rollback = r != null ? r : SystemPropertyRollback.noop();
            }
        }

        private boolean commitIsolatedClassLoader(ClassLoader loader) {
            if (committedLoader != null) {
                return committedLoader == loader;
            }
            synchronized (this) {
                if (committedLoader != null) {
                    return committedLoader == loader;
                }
                committedLoader = loader;
                return true;
            }
        }

        private boolean isCommittedLoader(ClassLoader loader) {
            return committedLoader == loader;
        }

        private boolean tryStartCleanup() {
            return cleanupStarted.compareAndSet(false, true);
        }

        private void cleanupBestEffort(ClassLoader loaderOrNull) {
            // Roll back sysprops first so downstream config reload sees the restored baseline ASAP.
            try {
                SystemPropertyRollback r = rollback;
                if (r != null) {
                    r.rollbackBestEffort();
                }
            } catch (Throwable ignore) {
                // best-effort
            }

            // Close classloader (prefer explicit arg to cover "created but not committed" failures).
            ClassLoader toClose = loaderOrNull != null ? loaderOrNull : committedLoader;
            closeBestEffort(toClose);

            // Clear strong refs (best-effort).
            rollback = null;
            committedLoader = null;
        }
    }
}
