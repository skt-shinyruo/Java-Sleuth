package com.javasleuth.core.command;

import com.javasleuth.core.command.session.ClientSessionRegistry;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.InputValidator;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;

/**
 * Resolved command-processor runtime dependencies.
 */
final class RuntimeServices {
    final Instrumentation instrumentation;
    final SleuthClassFileTransformer transformer;
    final Runnable shutdownHook;
    final ProductionConfig config;
    final SleuthConfig typedConfig;
    final AuditLogger auditLogger;
    final InputValidator inputValidator;
    final AuthenticationManager authenticationManager;
    final AuthorizationManager authorizationManager;
    final DangerousCommandConfirmationManager dangerousConfirm;
    final ClientSessionRegistry clientSessionRegistry;
    final MetricsCollector metricsCollector;
    final JobManager jobManager;
    final VmToolSessionRegistry vmToolSessionRegistry;
    final PerformanceOptimizer performanceOptimizer;
    final SleuthSpyDispatcher spyDispatcher;
    final EnhancementSessionRegistry enhancementSessionRegistry;
    final ResourceOwnership ownership;

    RuntimeServices(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        ProductionConfig config,
        SleuthConfig typedConfig,
        AuditLogger auditLogger,
        InputValidator inputValidator,
        AuthenticationManager authenticationManager,
        AuthorizationManager authorizationManager,
        DangerousCommandConfirmationManager dangerousConfirm,
        ClientSessionRegistry clientSessionRegistry,
        MetricsCollector metricsCollector,
        JobManager jobManager,
        VmToolSessionRegistry vmToolSessionRegistry,
        PerformanceOptimizer performanceOptimizer,
        SleuthSpyDispatcher spyDispatcher,
        EnhancementSessionRegistry enhancementSessionRegistry,
        ResourceOwnership ownership
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.shutdownHook = shutdownHook;
        this.config = config;
        this.typedConfig = typedConfig;
        this.auditLogger = auditLogger;
        this.inputValidator = inputValidator;
        this.authenticationManager = authenticationManager;
        this.authorizationManager = authorizationManager;
        this.dangerousConfirm = dangerousConfirm;
        this.clientSessionRegistry = clientSessionRegistry;
        this.metricsCollector = metricsCollector;
        this.jobManager = jobManager;
        this.vmToolSessionRegistry = vmToolSessionRegistry;
        this.performanceOptimizer = performanceOptimizer;
        this.spyDispatcher = spyDispatcher;
        this.enhancementSessionRegistry = enhancementSessionRegistry;
        this.ownership = ownership;
    }
}
