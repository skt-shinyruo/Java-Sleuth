package com.javasleuth.bootstrap.agent;

import com.javasleuth.bootstrap.util.AgentArgsApplier;
import com.javasleuth.bootstrap.util.SystemPropertyRollback;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
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
    private static final AtomicReference<CleanupReport> LAST_RUNTIME_CLEANUP =
        new AtomicReference<CleanupReport>(CleanupReport.notRun("runtime"));
    private static final AtomicReference<CleanupReport> LAST_BOOTSTRAP_CLEANUP =
        new AtomicReference<CleanupReport>(CleanupReport.notRun("bootstrap"));

    private AgentLifecycle() {}

    public static int contractVersion() {
        return 1;
    }

    public static void recordRuntimeCleanupResult(String status, String summary) {
        CleanupReport report = CleanupReport.external("runtime", status, summary);
        LAST_RUNTIME_CLEANUP.set(report);
        logCleanupReport(report);
    }

    public static String describeCleanupStatus() {
        CleanupReport runtime = LAST_RUNTIME_CLEANUP.get();
        CleanupReport bootstrap = LAST_BOOTSTRAP_CLEANUP.get();
        boolean degraded = (runtime != null && runtime.isDegraded()) || (bootstrap != null && bootstrap.isDegraded());

        StringBuilder sb = new StringBuilder();
        sb.append(degraded ? "DEGRADED" : "OK");
        if (degraded) {
            sb.append(" (partial cleanup recorded)");
        }
        sb.append("; runtime=").append(runtime != null ? runtime.shortStatus() : "NOT_RUN");
        sb.append("; bootstrap=").append(bootstrap != null ? bootstrap.shortStatus() : "NOT_RUN");

        String failures = combineFailures(runtime, bootstrap);
        if (failures.length() > 0) {
            sb.append("; failures=").append(failures);
        }
        return sb.toString();
    }

    public static boolean isCleanupDegraded() {
        CleanupReport runtime = LAST_RUNTIME_CLEANUP.get();
        CleanupReport bootstrap = LAST_BOOTSTRAP_CLEANUP.get();
        return (runtime != null && runtime.isDegraded()) || (bootstrap != null && bootstrap.isDegraded());
    }

    public static void clearCleanupStatusForTests() {
        LAST_RUNTIME_CLEANUP.set(CleanupReport.notRun("runtime"));
        LAST_BOOTSTRAP_CLEANUP.set(CleanupReport.notRun("bootstrap"));
    }

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
            publishBootstrapCleanup(closeOnlyReport("fail_no_session", loaderOrNull));
            return;
        }
        if (session.sessionId != sessionId) {
            publishBootstrapCleanup(closeOnlyReport("fail_wrong_session", loaderOrNull));
            return;
        }
        if (!session.tryStartCleanup()) {
            publishBootstrapCleanup(closeOnlyReport("fail_cleanup_already_started", loaderOrNull));
            return;
        }
        try {
            publishBootstrapCleanup(session.cleanupBestEffort(loaderOrNull, "fail"));
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
            publishBootstrapCleanup(session.cleanupBestEffort(null, "detach"));
        } finally {
            CURRENT.compareAndSet(session, null);
        }
    }

    static ClassLoader getCommittedIsolatedClassLoaderBestEffort() {
        AttachSession session = CURRENT.get();
        return session != null ? session.committedLoader : null;
    }

    private static CleanupReport closeOnlyReport(String reason, ClassLoader loader) {
        CleanupReport.Builder builder = CleanupReport.builder("bootstrap", reason);
        closeClassLoaderStep(builder, loader);
        return builder.build();
    }

    private static void publishBootstrapCleanup(CleanupReport report) {
        if (report == null) {
            return;
        }
        LAST_BOOTSTRAP_CLEANUP.set(report);
        logCleanupReport(report);
    }

    private static void logCleanupReport(CleanupReport report) {
        if (report == null || !report.isDegraded()) {
            return;
        }
        System.err.println("Java-Sleuth: " + report.scope + " cleanup completed with failures: " + report.failureSummary());
    }

    private static String combineFailures(CleanupReport runtime, CleanupReport bootstrap) {
        StringBuilder sb = new StringBuilder();
        appendFailure(sb, "runtime", runtime);
        appendFailure(sb, "bootstrap", bootstrap);
        return sb.toString();
    }

    private static void appendFailure(StringBuilder sb, String scope, CleanupReport report) {
        if (report == null || !report.isDegraded()) {
            return;
        }
        String failures = report.failureSummary();
        if (failures == null || failures.length() == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("; ");
        }
        sb.append(scope).append(": ").append(failures);
    }

    private static void closeClassLoaderStep(CleanupReport.Builder builder, ClassLoader loader) {
        if (!(loader instanceof Closeable)) {
            builder.skipped("close-classloader", loader == null ? "no classloader" : "not closeable");
            return;
        }
        try {
            ((Closeable) loader).close();
            builder.success("close-classloader");
        } catch (Throwable t) {
            builder.failure("close-classloader", t);
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

        private CleanupReport cleanupBestEffort(ClassLoader loaderOrNull, String reason) {
            CleanupReport.Builder builder = CleanupReport.builder("bootstrap", reason);

            // Roll back sysprops first so downstream config reload sees the restored baseline ASAP.
            try {
                SystemPropertyRollback r = rollback;
                if (r != null) {
                    r.rollbackBestEffort();
                    builder.success("rollback-system-properties");
                } else {
                    builder.skipped("rollback-system-properties", "no rollback handle");
                }
            } catch (Throwable t) {
                builder.failure("rollback-system-properties", t);
            }

            // Close classloader (prefer explicit arg to cover "created but not committed" failures).
            ClassLoader toClose = loaderOrNull != null ? loaderOrNull : committedLoader;
            closeClassLoaderStep(builder, toClose);

            // Clear strong refs (best-effort).
            try {
                rollback = null;
                committedLoader = null;
                builder.success("clear-bootstrap-references");
            } catch (Throwable t) {
                builder.failure("clear-bootstrap-references", t);
            }

            return builder.build();
        }
    }

    private static final class CleanupReport {
        private final String scope;
        private final String status;
        private final String summary;
        private final List<Step> steps;

        private CleanupReport(String scope, String status, String summary, List<Step> steps) {
            this.scope = clean(scope);
            this.status = clean(status);
            this.summary = clean(summary);
            this.steps = steps != null ? steps : new ArrayList<Step>();
        }

        private static CleanupReport notRun(String scope) {
            return new CleanupReport(scope, "NOT_RUN", "no cleanup has run", new ArrayList<Step>());
        }

        private static CleanupReport external(String scope, String status, String summary) {
            String normalized = status == null || status.trim().isEmpty() ? "UNKNOWN" : status.trim().toUpperCase();
            ArrayList<Step> steps = new ArrayList<Step>();
            if (!"OK".equals(normalized) && !"NOT_RUN".equals(normalized)) {
                steps.add(new Step("external-runtime-cleanup", false, summary));
            }
            return new CleanupReport(scope, normalized, summary, steps);
        }

        private static Builder builder(String scope, String reason) {
            return new Builder(scope, reason);
        }

        private boolean isDegraded() {
            if ("PARTIAL".equals(status) || "DEGRADED".equals(status) || "FAILED".equals(status)) {
                return true;
            }
            for (Step step : steps) {
                if (step != null && !step.success && !step.skipped) {
                    return true;
                }
            }
            return false;
        }

        private String shortStatus() {
            int failed = 0;
            int succeeded = 0;
            int skipped = 0;
            for (Step step : steps) {
                if (step == null) {
                    continue;
                }
                if (step.skipped) {
                    skipped++;
                } else if (step.success) {
                    succeeded++;
                } else {
                    failed++;
                }
            }
            return status + "(succeeded=" + succeeded + ", failed=" + failed + ", skipped=" + skipped + ")";
        }

        private String failureSummary() {
            StringBuilder sb = new StringBuilder();
            for (Step step : steps) {
                if (step == null || step.success || step.skipped) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(step.name);
                if (step.detail != null && step.detail.length() > 0) {
                    sb.append("=").append(step.detail);
                }
            }
            if (sb.length() == 0 && summary != null) {
                return summary;
            }
            return sb.toString();
        }

        private static final class Builder {
            private final String scope;
            private final String reason;
            private final ArrayList<Step> steps = new ArrayList<Step>();

            private Builder(String scope, String reason) {
                this.scope = scope;
                this.reason = reason;
            }

            private void success(String name) {
                steps.add(new Step(name, true, null));
            }

            private void skipped(String name, String detail) {
                steps.add(new Step(name, true, detail, true));
            }

            private void failure(String name, Throwable t) {
                steps.add(new Step(name, false, describe(t)));
            }

            private CleanupReport build() {
                boolean failed = false;
                for (Step step : steps) {
                    if (step != null && !step.success && !step.skipped) {
                        failed = true;
                        break;
                    }
                }
                String status = failed ? "PARTIAL" : "OK";
                return new CleanupReport(scope, status, "reason=" + reason, new ArrayList<Step>(steps));
            }
        }

        private static final class Step {
            private final String name;
            private final boolean success;
            private final boolean skipped;
            private final String detail;

            private Step(String name, boolean success, String detail) {
                this(name, success, detail, false);
            }

            private Step(String name, boolean success, String detail, boolean skipped) {
                this.name = clean(name);
                this.success = success;
                this.detail = clean(detail);
                this.skipped = skipped;
            }
        }

        private static String describe(Throwable t) {
            if (t == null) {
                return "unknown";
            }
            String message = t.getMessage();
            if (message == null || message.trim().isEmpty()) {
                return t.getClass().getName();
            }
            return t.getClass().getName() + ": " + clean(message);
        }

        private static String clean(String value) {
            if (value == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\r' || c == '\n' || c == '\t') {
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
