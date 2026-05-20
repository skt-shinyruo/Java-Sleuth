package com.javasleuth.core.command;

import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.command.spi.RestrictedCommandProviderContext;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;

/**
 * Attach-scope command provider context.
 *
 * <p>Provides runtime-owned services to builtin and plugin command providers without forcing them to
 * reach into global singletons.</p>
 */
public final class CommandProviderContext {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final MetricsCollector metricsCollector;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final Runnable shutdownHook;
    private final AuthenticationManager authenticationManager;
    private final DangerousCommandConfirmationManager dangerousConfirm;
    private final JobManager jobManager;
    private final VmToolSessionRegistry vmToolSessionRegistry;
    private final PerformanceOptimizer performanceOptimizer;
    private final SleuthSpyDispatcher spyDispatcher;
    private final EnhancementSessionRegistry enhancementSessionRegistry;

    public CommandProviderContext(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        MetricsCollector metricsCollector,
        ProductionConfig config,
        AuditLogger auditLogger,
        Runnable shutdownHook,
        AuthenticationManager authenticationManager,
        DangerousCommandConfirmationManager dangerousConfirm,
        JobManager jobManager,
        VmToolSessionRegistry vmToolSessionRegistry,
        PerformanceOptimizer performanceOptimizer,
        SleuthSpyDispatcher spyDispatcher
    ) {
        this(
            instrumentation,
            transformer,
            metricsCollector,
            config,
            auditLogger,
            shutdownHook,
            authenticationManager,
            dangerousConfirm,
            jobManager,
            vmToolSessionRegistry,
            performanceOptimizer,
            spyDispatcher,
            null
        );
    }

    public CommandProviderContext(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        MetricsCollector metricsCollector,
        ProductionConfig config,
        AuditLogger auditLogger,
        Runnable shutdownHook,
        AuthenticationManager authenticationManager,
        DangerousCommandConfirmationManager dangerousConfirm,
        JobManager jobManager,
        VmToolSessionRegistry vmToolSessionRegistry,
        PerformanceOptimizer performanceOptimizer,
        SleuthSpyDispatcher spyDispatcher,
        EnhancementSessionRegistry enhancementSessionRegistry
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.metricsCollector = metricsCollector;
        this.config = config;
        this.auditLogger = auditLogger;
        this.shutdownHook = shutdownHook;
        this.authenticationManager = authenticationManager;
        this.dangerousConfirm = dangerousConfirm;
        this.jobManager = jobManager;
        this.vmToolSessionRegistry = vmToolSessionRegistry;
        this.performanceOptimizer = performanceOptimizer;
        this.spyDispatcher = spyDispatcher;
        this.enhancementSessionRegistry = enhancementSessionRegistry;
    }

    public Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public Instrumentation requireInstrumentation() {
        return requireNonNull(instrumentation, "instrumentation");
    }

    public SleuthClassFileTransformer getTransformer() {
        return transformer;
    }

    public SleuthClassFileTransformer requireTransformer() {
        return requireNonNull(transformer, "transformer");
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public MetricsCollector requireMetricsCollector() {
        return requireNonNull(metricsCollector, "metricsCollector");
    }

    public ProductionConfig getConfig() {
        return config;
    }

    public ProductionConfig requireConfig() {
        return requireNonNull(config, "config");
    }

    public AuditLogger getAuditLogger() {
        return auditLogger;
    }

    public AuditLogger requireAuditLogger() {
        return requireNonNull(auditLogger, "auditLogger");
    }

    public Runnable getShutdownHook() {
        return shutdownHook;
    }

    public AuthenticationManager getAuthenticationManager() {
        return authenticationManager;
    }

    public AuthenticationManager requireAuthenticationManager() {
        return requireNonNull(authenticationManager, "authenticationManager");
    }

    public DangerousCommandConfirmationManager getDangerousConfirm() {
        return dangerousConfirm;
    }

    public DangerousCommandConfirmationManager requireDangerousConfirm() {
        return requireNonNull(dangerousConfirm, "dangerousConfirm");
    }

    public JobManager getJobManager() {
        return jobManager;
    }

    public JobManager requireJobManager() {
        return requireNonNull(jobManager, "jobManager");
    }

    public VmToolSessionRegistry getVmToolSessionRegistry() {
        return vmToolSessionRegistry;
    }

    public VmToolSessionRegistry requireVmToolSessionRegistry() {
        return requireNonNull(vmToolSessionRegistry, "vmToolSessionRegistry");
    }

    public PerformanceOptimizer getPerformanceOptimizer() {
        return performanceOptimizer;
    }

    public PerformanceOptimizer requirePerformanceOptimizer() {
        return requireNonNull(performanceOptimizer, "performanceOptimizer");
    }

    public SleuthSpyDispatcher getSpyDispatcher() {
        return spyDispatcher;
    }

    public SleuthSpyDispatcher requireSpyDispatcher() {
        return requireNonNull(spyDispatcher, "spyDispatcher");
    }

    public EnhancementSessionRegistry getEnhancementSessionRegistry() {
        return enhancementSessionRegistry;
    }

    public EnhancementSessionRegistry requireEnhancementSessionRegistry() {
        return requireNonNull(enhancementSessionRegistry, "enhancementSessionRegistry");
    }

    CommandProviderContext restrictedCopy() {
        return new CommandProviderContext(
            null,
            null,
            null,
            config,
            auditLogger,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public RestrictedCommandProviderContext toRestrictedContext() {
        return new RestrictedCommandProviderContext(config, auditLogger);
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalStateException("CommandProviderContext missing required dependency: " + name);
        }
        return value;
    }
}
