package com.javasleuth.core.command;

import com.javasleuth.core.command.session.ClientSessionRegistry;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.InputValidator;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import com.javasleuth.foundation.util.SleuthLogger;
import java.lang.instrument.Instrumentation;

/**
 * Resolves command-processor runtime services and ownership.
 */
final class RuntimeServicesFactory {
    private RuntimeServicesFactory() {}

    static RuntimeServices create(CommandProcessorFactoryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }

        Instrumentation instrumentation = request.getInstrumentation();
        SleuthClassFileTransformer transformer = request.getTransformer();
        if (instrumentation == null) {
            throw new IllegalArgumentException("instrumentation is required");
        }
        if (transformer == null) {
            throw new IllegalArgumentException("transformer is required");
        }

        ProductionConfig cfg = request.getConfig() != null
            ? request.getConfig()
            : ProductionConfig.createDefault();

        boolean ownsAuditLogger = request.getAuditLogger() == null;
        AuditLogger audit = request.getAuditLogger() != null
            ? request.getAuditLogger()
            : new AuditLogger(cfg);

        boolean ownsAuthenticationManager = request.getAuthenticationManager() == null;
        AuthenticationManager authn = request.getAuthenticationManager() != null
            ? request.getAuthenticationManager()
            : new AuthenticationManager(cfg, audit);

        AuthorizationManager authz = request.getAuthorizationManager() != null
            ? request.getAuthorizationManager()
            : new AuthorizationManager(cfg, audit, authn);

        boolean ownsDangerousConfirm = request.getDangerousConfirm() == null;
        DangerousCommandConfirmationManager dangerous = request.getDangerousConfirm() != null
            ? request.getDangerousConfirm()
            : new DangerousCommandConfirmationManager(cfg, audit);

        boolean ownsClientSessionRegistry = request.getClientSessionRegistry() == null;
        ClientSessionRegistry clientSessions = request.getClientSessionRegistry() != null
            ? request.getClientSessionRegistry()
            : new ClientSessionRegistry();

        boolean ownsJobManager = request.getJobManager() == null;
        JobManager jobs = request.getJobManager() != null
            ? request.getJobManager()
            : new JobManager();

        boolean ownsVmToolSessionRegistry = request.getVmToolSessionRegistry() == null;
        VmToolSessionRegistry vmtool = request.getVmToolSessionRegistry() != null
            ? request.getVmToolSessionRegistry()
            : new VmToolSessionRegistry();

        boolean ownsPerformanceOptimizer = request.getPerformanceOptimizer() == null;
        PerformanceOptimizer perf = request.getPerformanceOptimizer() != null
            ? request.getPerformanceOptimizer()
            : new PerformanceOptimizer(cfg);

        SleuthSpyDispatcher dispatcher = request.getSpyDispatcher() != null
            ? request.getSpyDispatcher()
            : new SleuthSpyDispatcher();

        boolean ownsEnhancementSessionRegistry = request.getEnhancementSessionRegistry() == null;
        EnhancementSessionRegistry enhancementSessions = request.getEnhancementSessionRegistry() != null
            ? request.getEnhancementSessionRegistry()
            : new EnhancementSessionRegistry();

        MetricsCollector metrics = request.getMetricsCollector() != null
            ? request.getMetricsCollector()
            : new MetricsCollector(cfg);

        try {
            metrics.recordAuditDropped(audit.getDroppedCount());
        } catch (Exception e) {
            SleuthLogger.debug("Failed to record initial audit dropped count: " + e.getMessage(), e);
        }

        SleuthConfig typedConfig = SleuthConfigParser.parse(cfg.snapshot());
        InputValidator inputValidator = new InputValidator(cfg, audit);

        ResourceOwnership ownership = new ResourceOwnership(
            ownsAuditLogger,
            ownsAuthenticationManager,
            ownsDangerousConfirm,
            ownsPerformanceOptimizer,
            ownsVmToolSessionRegistry,
            ownsClientSessionRegistry,
            ownsJobManager,
            ownsEnhancementSessionRegistry
        );

        return new RuntimeServices(
            instrumentation,
            transformer,
            request.getShutdownHook(),
            cfg,
            typedConfig,
            audit,
            inputValidator,
            authn,
            authz,
            dangerous,
            clientSessions,
            metrics,
            jobs,
            vmtool,
            perf,
            dispatcher,
            enhancementSessions,
            ownership
        );
    }
}
