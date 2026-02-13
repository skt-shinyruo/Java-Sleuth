package com.javasleuth.command;

/**
 * CommandProcessor 的默认装配工厂（composition root 辅助）。
 *
 * <p>集中处理：线程池、registry/pipeline、server 生命周期组件、会话索引等装配，避免 CommandProcessor 继续膨胀。</p>
 */
public final class CommandProcessorFactory {
    private CommandProcessorFactory() {}

    public static CommandProcessor createDefault(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.enhancement.SleuthClassFileTransformer transformer
    ) {
        return new CommandProcessor(createComponents(instrumentation, transformer, null));
    }

    public static CommandProcessor createDefault(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook
    ) {
        return new CommandProcessor(createComponents(instrumentation, transformer, shutdownHook));
    }

    public static CommandProcessor create(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        com.javasleuth.config.ProductionConfig config,
        com.javasleuth.security.AuditLogger auditLogger,
        com.javasleuth.security.AuthenticationManager authenticationManager,
        com.javasleuth.security.AuthorizationManager authorizationManager,
        com.javasleuth.security.RequestSecurityManager requestSecurityManager,
        com.javasleuth.security.DangerousCommandConfirmationManager dangerousConfirm,
        com.javasleuth.command.session.ClientSessionRegistry clientSessionRegistry,
        com.javasleuth.monitoring.MetricsCollector metricsCollector
    ) {
        CommandProcessorComponents components = createComponents(
            instrumentation,
            transformer,
            shutdownHook,
            config,
            auditLogger,
            authenticationManager,
            authorizationManager,
            requestSecurityManager,
            dangerousConfirm,
            clientSessionRegistry,
            metricsCollector
        );
        return new CommandProcessor(components);
    }

    public static CommandProcessorComponents createComponents(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook
    ) {
        return createComponents(
            instrumentation,
            transformer,
            shutdownHook,
            com.javasleuth.config.ProductionConfig.getInstance(),
            com.javasleuth.security.AuditLogger.getInstance(),
            com.javasleuth.security.AuthenticationManager.getInstance(),
            com.javasleuth.security.AuthorizationManager.getInstance(),
            com.javasleuth.security.RequestSecurityManager.getInstance(),
            com.javasleuth.security.DangerousCommandConfirmationManager.getInstance(),
            com.javasleuth.command.session.ClientSessionRegistry.getInstance(),
            new com.javasleuth.monitoring.MetricsCollector()
        );
    }

    public static CommandProcessorComponents createComponents(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        com.javasleuth.config.ProductionConfig config,
        com.javasleuth.security.AuditLogger auditLogger,
        com.javasleuth.security.AuthenticationManager authenticationManager,
        com.javasleuth.security.AuthorizationManager authorizationManager,
        com.javasleuth.security.RequestSecurityManager requestSecurityManager,
        com.javasleuth.security.DangerousCommandConfirmationManager dangerousConfirm,
        com.javasleuth.command.session.ClientSessionRegistry clientSessionRegistry,
        com.javasleuth.monitoring.MetricsCollector metricsCollector
    ) {
        if (instrumentation == null) {
            throw new IllegalArgumentException("instrumentation is required");
        }
        if (transformer == null) {
            throw new IllegalArgumentException("transformer is required");
        }

        com.javasleuth.config.ProductionConfig cfg =
            config != null ? config : com.javasleuth.config.ProductionConfig.getInstance();
        com.javasleuth.security.AuditLogger audit =
            auditLogger != null ? auditLogger : com.javasleuth.security.AuditLogger.getInstance();
        com.javasleuth.security.AuthenticationManager authn =
            authenticationManager != null ? authenticationManager : com.javasleuth.security.AuthenticationManager.getInstance();
        com.javasleuth.security.AuthorizationManager authz =
            authorizationManager != null ? authorizationManager : com.javasleuth.security.AuthorizationManager.getInstance();
        com.javasleuth.security.RequestSecurityManager reqSec =
            requestSecurityManager != null ? requestSecurityManager : com.javasleuth.security.RequestSecurityManager.getInstance();
        com.javasleuth.security.DangerousCommandConfirmationManager dc =
            dangerousConfirm != null ? dangerousConfirm : com.javasleuth.security.DangerousCommandConfirmationManager.getInstance();
        com.javasleuth.command.session.ClientSessionRegistry csr =
            clientSessionRegistry != null ? clientSessionRegistry : com.javasleuth.command.session.ClientSessionRegistry.getInstance();

        com.javasleuth.command.server.ServerBootstrapper bootstrapper = new com.javasleuth.command.server.ServerBootstrapper();
        bootstrapper.configureLoggingProvider(cfg);
        bootstrapper.configureJobManager(cfg);

        com.javasleuth.security.InputValidator inputValidator = new com.javasleuth.security.InputValidator();

        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicLong commandCounter = new java.util.concurrent.atomic.AtomicLong(0);

        java.util.concurrent.ThreadPoolExecutor clientExecutor = new java.util.concurrent.ThreadPoolExecutor(
            cfg.getThreadPoolCoreSize(),
            cfg.getThreadPoolMaxSize(),
            60L,
            java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(cfg.getServerExecutorQueueCapacity()),
            com.javasleuth.util.SleuthThreadFactory.daemon("sleuth-client"),
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
        );

        com.javasleuth.monitoring.MetricsCollector metrics =
            metricsCollector != null ? metricsCollector : new com.javasleuth.monitoring.MetricsCollector();

        // 将审计丢弃计数暴露到 metrics/health（best-effort，避免装配失败）。
        try {
            metrics.recordAuditDropped(audit.getDroppedCount());
        } catch (Exception e) {
            com.javasleuth.util.SleuthLogger.debug("Failed to record initial audit dropped count: " + e.getMessage(), e);
        }

        CommandRegistry registry = new CommandRegistry(
            instrumentation,
            transformer,
            metrics,
            cfg,
            audit,
            shutdownHook,
            authn,
            dc
        );

        CommandPipeline pipeline = new CommandPipeline(inputValidator, authz, dc, cfg);

        com.javasleuth.command.session.ClientSessionIndex sessionIndex = new com.javasleuth.command.session.ClientSessionIndex();

        com.javasleuth.command.server.CommandClientHandler clientHandler = new com.javasleuth.command.server.CommandClientHandler(
            running,
            commandCounter,
            metrics,
            cfg,
            audit,
            authn,
            reqSec,
            registry,
            pipeline,
            sessionIndex,
            csr
        );

        com.javasleuth.command.server.ConnectionAcceptor acceptor = new com.javasleuth.command.server.ConnectionAcceptor();

        com.javasleuth.command.server.ShutdownCoordinator shutdownCoordinator = new com.javasleuth.command.server.ShutdownCoordinator(
            running,
            clientExecutor,
            metrics,
            audit,
            registry,
            pipeline,
            authn,
            authz,
            reqSec,
            dc
        );

        try {
            audit.logSystemEvent(
                "COMMAND_PROCESSOR_INIT",
                "Command processor initialized with " + registry.getCommandMap().size() + " commands"
            );
        } catch (Exception ignore) {
            // ignore
        }

        return new CommandProcessorComponents(
            instrumentation,
            transformer,
            shutdownHook,
            running,
            commandCounter,
            clientExecutor,
            metrics,
            cfg,
            audit,
            inputValidator,
            authn,
            authz,
            reqSec,
            dc,
            csr,
            sessionIndex,
            registry,
            pipeline,
            clientHandler,
            bootstrapper,
            acceptor,
            shutdownCoordinator
        );
    }
}

