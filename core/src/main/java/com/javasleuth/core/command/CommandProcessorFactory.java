package com.javasleuth.core.command;

/**
 * CommandProcessor 的默认装配工厂（composition root 辅助）。
 *
 * <p>集中处理：线程池、registry/pipeline、server 生命周期组件、会话索引等装配，避免 CommandProcessor 继续膨胀。</p>
 */
public final class CommandProcessorFactory {
    private CommandProcessorFactory() {}

    public static CommandProcessor createDefault(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer
    ) {
        return new CommandProcessor(createComponents(instrumentation, transformer, null));
    }

    public static CommandProcessor createDefault(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook
    ) {
        return new CommandProcessor(createComponents(instrumentation, transformer, shutdownHook));
    }

    public static CommandProcessor create(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        com.javasleuth.foundation.config.ProductionConfig config,
        com.javasleuth.foundation.security.AuditLogger auditLogger,
        com.javasleuth.foundation.security.AuthenticationManager authenticationManager,
        com.javasleuth.foundation.security.AuthorizationManager authorizationManager,
        com.javasleuth.foundation.security.DangerousCommandConfirmationManager dangerousConfirm,
        com.javasleuth.core.command.session.ClientSessionRegistry clientSessionRegistry,
        com.javasleuth.core.monitoring.MetricsCollector metricsCollector
    ) {
        return create(
            instrumentation,
            transformer,
            shutdownHook,
            config,
            auditLogger,
            authenticationManager,
            authorizationManager,
            dangerousConfirm,
            clientSessionRegistry,
            metricsCollector,
            null,
            null
        );
    }

    public static CommandProcessor create(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        com.javasleuth.foundation.config.ProductionConfig config,
        com.javasleuth.foundation.security.AuditLogger auditLogger,
        com.javasleuth.foundation.security.AuthenticationManager authenticationManager,
        com.javasleuth.foundation.security.AuthorizationManager authorizationManager,
        com.javasleuth.foundation.security.DangerousCommandConfirmationManager dangerousConfirm,
        com.javasleuth.core.command.session.ClientSessionRegistry clientSessionRegistry,
        com.javasleuth.core.monitoring.MetricsCollector metricsCollector,
        JobManager jobManager,
        com.javasleuth.core.vmtool.VmToolSessionRegistry vmToolSessionRegistry
    ) {
        CommandProcessorComponents components = createComponents(
            instrumentation,
            transformer,
            shutdownHook,
            config,
            auditLogger,
            authenticationManager,
            authorizationManager,
            dangerousConfirm,
            clientSessionRegistry,
            metricsCollector,
            jobManager,
            vmToolSessionRegistry
        );
        return new CommandProcessor(components);
    }

    public static CommandProcessor create(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        com.javasleuth.foundation.config.ProductionConfig config,
        com.javasleuth.foundation.security.AuditLogger auditLogger,
        com.javasleuth.foundation.security.AuthenticationManager authenticationManager,
        com.javasleuth.foundation.security.AuthorizationManager authorizationManager,
        com.javasleuth.foundation.security.DangerousCommandConfirmationManager dangerousConfirm,
        com.javasleuth.core.command.session.ClientSessionRegistry clientSessionRegistry,
        com.javasleuth.core.monitoring.MetricsCollector metricsCollector,
        JobManager jobManager,
        com.javasleuth.core.vmtool.VmToolSessionRegistry vmToolSessionRegistry,
        com.javasleuth.foundation.util.PerformanceOptimizer performanceOptimizer,
        com.javasleuth.core.spy.SleuthSpyDispatcher spyDispatcher
    ) {
        CommandProcessorComponents components = createComponents(
            instrumentation,
            transformer,
            shutdownHook,
            config,
            auditLogger,
            authenticationManager,
            authorizationManager,
            dangerousConfirm,
            clientSessionRegistry,
            metricsCollector,
            jobManager,
            vmToolSessionRegistry,
            performanceOptimizer,
            spyDispatcher
        );
        return new CommandProcessor(components);
    }

    public static CommandProcessorComponents createComponents(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook
    ) {
        return createComponents(
            instrumentation,
            transformer,
            shutdownHook,
            null,
            null,
            null,
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

    public static CommandProcessorComponents createComponents(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        com.javasleuth.foundation.config.ProductionConfig config,
        com.javasleuth.foundation.security.AuditLogger auditLogger,
        com.javasleuth.foundation.security.AuthenticationManager authenticationManager,
        com.javasleuth.foundation.security.AuthorizationManager authorizationManager,
        com.javasleuth.foundation.security.DangerousCommandConfirmationManager dangerousConfirm,
        com.javasleuth.core.command.session.ClientSessionRegistry clientSessionRegistry,
        com.javasleuth.core.monitoring.MetricsCollector metricsCollector
    ) {
        return createComponents(
            instrumentation,
            transformer,
            shutdownHook,
            config,
            auditLogger,
            authenticationManager,
            authorizationManager,
            dangerousConfirm,
            clientSessionRegistry,
            metricsCollector,
            null,
            null,
            null,
            null
        );
    }

    public static CommandProcessorComponents createComponents(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        com.javasleuth.foundation.config.ProductionConfig config,
        com.javasleuth.foundation.security.AuditLogger auditLogger,
        com.javasleuth.foundation.security.AuthenticationManager authenticationManager,
        com.javasleuth.foundation.security.AuthorizationManager authorizationManager,
        com.javasleuth.foundation.security.DangerousCommandConfirmationManager dangerousConfirm,
        com.javasleuth.core.command.session.ClientSessionRegistry clientSessionRegistry,
        com.javasleuth.core.monitoring.MetricsCollector metricsCollector,
        JobManager jobManager,
        com.javasleuth.core.vmtool.VmToolSessionRegistry vmToolSessionRegistry
    ) {
        return createComponents(
            instrumentation,
            transformer,
            shutdownHook,
            config,
            auditLogger,
            authenticationManager,
            authorizationManager,
            dangerousConfirm,
            clientSessionRegistry,
            metricsCollector,
            jobManager,
            vmToolSessionRegistry,
            null,
            null
        );
    }

    public static CommandProcessorComponents createComponents(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        com.javasleuth.foundation.config.ProductionConfig config,
        com.javasleuth.foundation.security.AuditLogger auditLogger,
        com.javasleuth.foundation.security.AuthenticationManager authenticationManager,
        com.javasleuth.foundation.security.AuthorizationManager authorizationManager,
        com.javasleuth.foundation.security.DangerousCommandConfirmationManager dangerousConfirm,
        com.javasleuth.core.command.session.ClientSessionRegistry clientSessionRegistry,
        com.javasleuth.core.monitoring.MetricsCollector metricsCollector,
        JobManager jobManager,
        com.javasleuth.core.vmtool.VmToolSessionRegistry vmToolSessionRegistry,
        com.javasleuth.foundation.util.PerformanceOptimizer performanceOptimizer,
        com.javasleuth.core.spy.SleuthSpyDispatcher spyDispatcher
    ) {
        if (instrumentation == null) {
            throw new IllegalArgumentException("instrumentation is required");
        }
        if (transformer == null) {
            throw new IllegalArgumentException("transformer is required");
        }

        boolean ownsJobManager = jobManager == null;
        boolean ownsVmToolSessionRegistry = vmToolSessionRegistry == null;
        boolean ownsPerformanceOptimizer = performanceOptimizer == null;

        com.javasleuth.foundation.config.ProductionConfig cfg =
            config != null ? config : com.javasleuth.foundation.config.ProductionConfig.createDefault();

        boolean ownsAuditLogger = auditLogger == null;
        com.javasleuth.foundation.security.AuditLogger audit =
            auditLogger != null ? auditLogger : new com.javasleuth.foundation.security.AuditLogger(cfg);

        boolean ownsAuthenticationManager = authenticationManager == null;
        com.javasleuth.foundation.security.AuthenticationManager authn =
            authenticationManager != null
                ? authenticationManager
                : new com.javasleuth.foundation.security.AuthenticationManager(cfg, audit);
        com.javasleuth.foundation.security.AuthorizationManager authz =
            authorizationManager != null ? authorizationManager : new com.javasleuth.foundation.security.AuthorizationManager(cfg, audit, authn);

        boolean ownsDangerousConfirm = dangerousConfirm == null;
        com.javasleuth.foundation.security.DangerousCommandConfirmationManager dc =
            dangerousConfirm != null
                ? dangerousConfirm
                : new com.javasleuth.foundation.security.DangerousCommandConfirmationManager(cfg, audit);

        boolean ownsClientSessionRegistry = clientSessionRegistry == null;
        com.javasleuth.core.command.session.ClientSessionRegistry csr =
            clientSessionRegistry != null ? clientSessionRegistry : new com.javasleuth.core.command.session.ClientSessionRegistry();

        JobManager jm = jobManager != null ? jobManager : new JobManager();
        com.javasleuth.core.vmtool.VmToolSessionRegistry vmsr =
            vmToolSessionRegistry != null ? vmToolSessionRegistry : new com.javasleuth.core.vmtool.VmToolSessionRegistry();
        com.javasleuth.foundation.util.PerformanceOptimizer perf =
            performanceOptimizer != null ? performanceOptimizer : com.javasleuth.foundation.util.PerformanceOptimizer.getInstance(cfg);
        com.javasleuth.core.spy.SleuthSpyDispatcher dispatcher =
            spyDispatcher != null ? spyDispatcher : new com.javasleuth.core.spy.SleuthSpyDispatcher();

        com.javasleuth.foundation.config.model.SleuthConfig typedConfig =
            com.javasleuth.foundation.config.model.SleuthConfigParser.parse(cfg.snapshot());

        com.javasleuth.core.command.server.ServerBootstrapper bootstrapper = new com.javasleuth.core.command.server.ServerBootstrapper();
        bootstrapper.configureLoggingProvider(cfg);
        bootstrapper.configureJobManager(jm, cfg);

        com.javasleuth.foundation.security.InputValidator inputValidator = new com.javasleuth.foundation.security.InputValidator(cfg, audit);

        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicLong commandCounter = new java.util.concurrent.atomic.AtomicLong(0);

        java.util.concurrent.ThreadPoolExecutor clientExecutor = new java.util.concurrent.ThreadPoolExecutor(
            typedConfig.performance().getThreadPoolCoreSize(),
            typedConfig.performance().getThreadPoolMaxSize(),
            60L,
            java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(typedConfig.server().getExecutorQueueCapacity()),
            com.javasleuth.foundation.util.SleuthThreadFactory.daemon("sleuth-client"),
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
        );

        com.javasleuth.core.monitoring.MetricsCollector metrics =
            metricsCollector != null ? metricsCollector : new com.javasleuth.core.monitoring.MetricsCollector(cfg);

        // 将审计丢弃计数暴露到 metrics/health（best-effort，避免装配失败）。
        try {
            metrics.recordAuditDropped(audit.getDroppedCount());
        } catch (Exception e) {
            com.javasleuth.foundation.util.SleuthLogger.debug("Failed to record initial audit dropped count: " + e.getMessage(), e);
        }

        BuiltinCommandProvider builtinProvider = new BuiltinCommandProvider(
            instrumentation,
            transformer,
            metrics,
            cfg,
            audit,
            shutdownHook,
            authn,
            dc,
            jm,
            vmsr,
            perf,
            dispatcher
        );

        com.javasleuth.core.command.plugin.CommandProviderLoader providerLoader =
            new com.javasleuth.core.command.plugin.CommandProviderLoader(cfg, audit, CommandProcessorFactory.class.getClassLoader());
        com.javasleuth.core.command.plugin.CommandProviderLoader.LoadedProviders loadedProviders =
            providerLoader.load(builtinProvider);

        CommandRegistry registry = new CommandRegistry(
            cfg,
            metrics,
            audit,
            loadedProviders.getProviders(),
            loadedProviders.getPluginClassLoader()
        );

        CommandPipeline pipeline = new CommandPipeline(inputValidator, authz, dc, cfg);

        com.javasleuth.core.command.session.ClientSessionIndex sessionIndex = new com.javasleuth.core.command.session.ClientSessionIndex();

        com.javasleuth.core.command.server.CommandClientHandler clientHandler = new com.javasleuth.core.command.server.CommandClientHandler(
            running,
            commandCounter,
            metrics,
            cfg,
            audit,
            authn,
            registry,
            pipeline,
            sessionIndex,
            csr
        );

        com.javasleuth.core.command.server.ConnectionAcceptor acceptor = new com.javasleuth.core.command.server.ConnectionAcceptor();

        com.javasleuth.core.command.server.ShutdownCoordinator shutdownCoordinator = new com.javasleuth.core.command.server.ShutdownCoordinator(
            running,
            clientExecutor,
            metrics,
            audit,
            registry,
            pipeline,
            authz
        );

        AutoCloseable ownedResources = null;
        if (ownsAuditLogger
            || ownsAuthenticationManager
            || ownsDangerousConfirm
            || ownsClientSessionRegistry
            || ownsJobManager
            || ownsVmToolSessionRegistry
            || ownsPerformanceOptimizer) {
            java.util.ArrayList<AutoCloseable> closeables = new java.util.ArrayList<>();

            // Close order is reversed: add dependencies first so they close last.
            if (ownsAuditLogger) {
                closeables.add(audit);
            }
            if (ownsAuthenticationManager) {
                closeables.add(authn);
            }
            if (ownsDangerousConfirm) {
                closeables.add(dc);
            }
            if (ownsPerformanceOptimizer) {
                closeables.add(() -> com.javasleuth.foundation.util.PerformanceOptimizer.shutdown());
            }
            if (ownsVmToolSessionRegistry) {
                closeables.add(() -> vmsr.shutdown(instrumentation, transformer, "shutdown"));
            }
            if (ownsClientSessionRegistry) {
                closeables.add(() -> csr.shutdown("shutdown"));
            }
            if (ownsJobManager) {
                closeables.add(() -> jm.shutdown("shutdown"));
            }

            ownedResources = new CommandProcessorOwnedResources(closeables.toArray(new AutoCloseable[0]));
        }

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
            dc,
            csr,
            sessionIndex,
            registry,
            pipeline,
            clientHandler,
            bootstrapper,
            acceptor,
            shutdownCoordinator,
            ownedResources
        );
    }
}
