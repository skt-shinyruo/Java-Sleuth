package com.javasleuth.core.agent.runtime;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.util.MemoryOptimizer;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Attach-scope services container.
 *
 * <p>All global resources that must be released on detach (threads/executors, JMX MBeans, caches, singletons)
 * should be owned by an attach lifecycle object and closed via a single path ({@link #close()}), similar to
 * Arthas' {@code destroy()} style of shutdown.
 *
 * <p>Security services and optimizers are attach-scope instances owned by the runtime.
 */
public final class SleuthAgentServices implements AutoCloseable {
    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final AuthenticationManager authenticationManager;
    private final DangerousCommandConfirmationManager dangerousConfirm;
    private final PerformanceOptimizer performanceOptimizer;
    private final MemoryOptimizer memoryOptimizer;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private SleuthAgentServices(
        ProductionConfig config,
        AuditLogger auditLogger,
        AuthenticationManager authenticationManager,
        DangerousCommandConfirmationManager dangerousConfirm,
        PerformanceOptimizer performanceOptimizer,
        MemoryOptimizer memoryOptimizer
    ) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (auditLogger == null) {
            throw new IllegalArgumentException("auditLogger is required");
        }
        if (authenticationManager == null) {
            throw new IllegalArgumentException("authenticationManager is required");
        }
        if (dangerousConfirm == null) {
            throw new IllegalArgumentException("dangerousConfirm is required");
        }
        if (performanceOptimizer == null) {
            throw new IllegalArgumentException("performanceOptimizer is required");
        }
        if (memoryOptimizer == null) {
            throw new IllegalArgumentException("memoryOptimizer is required");
        }
        this.config = config;
        this.auditLogger = auditLogger;
        this.authenticationManager = authenticationManager;
        this.dangerousConfirm = dangerousConfirm;
        this.performanceOptimizer = performanceOptimizer;
        this.memoryOptimizer = memoryOptimizer;
    }

    public static SleuthAgentServices createDefault() {
        ProductionConfig config = ProductionConfig.createDefault();

        // Sync attach-scope monitoring config into bootstrap store early, so interceptors see consistent flags.
        try {
            BootstrapMonitoringConfigSync.syncFromConfigViewBestEffort(config);
        } catch (Exception ignore) {
            // best-effort
        }

        AuditLogger auditLogger = new AuditLogger(config);
        AuthenticationManager authenticationManager = new AuthenticationManager(config, auditLogger);
        DangerousCommandConfirmationManager dangerousConfirm = new DangerousCommandConfirmationManager(config, auditLogger);
        PerformanceOptimizer performanceOptimizer = new PerformanceOptimizer(config);
        MemoryOptimizer memoryOptimizer = new MemoryOptimizer(config, performanceOptimizer);
        return new SleuthAgentServices(
            config,
            auditLogger,
            authenticationManager,
            dangerousConfirm,
            performanceOptimizer,
            memoryOptimizer
        );
    }

    public ProductionConfig getConfig() {
        return config;
    }

    public AuditLogger getAuditLogger() {
        return auditLogger;
    }

    public AuthenticationManager getAuthenticationManager() {
        return authenticationManager;
    }

    public DangerousCommandConfirmationManager getDangerousConfirm() {
        return dangerousConfirm;
    }

    public PerformanceOptimizer getPerformanceOptimizer() {
        return performanceOptimizer;
    }

    public MemoryOptimizer getMemoryOptimizer() {
        return memoryOptimizer;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        // 1) MemoryOptimizer holds a scheduled executor + registers a JMX MBean.
        // It's currently unused by default; shutdown is best-effort and no-op if never started.
        try {
            memoryOptimizer.close();
        } catch (Exception ignore) {
            // best-effort
        }

        // 2) Performance optimizer (thread pools + JMX).
        try {
            performanceOptimizer.close();
        } catch (Exception ignore) {
            // best-effort
        }

        // 3) Security services (may hold threads / caches). Close before audit logger, so shutdown can be logged.
        try {
            dangerousConfirm.close();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            authenticationManager.close();
        } catch (Exception ignore) {
            // best-effort
        }

        // 4) Audit logger (thread + file handles).
        try {
            auditLogger.close();
        } catch (Exception ignore) {
            // best-effort
        }
    }
}
