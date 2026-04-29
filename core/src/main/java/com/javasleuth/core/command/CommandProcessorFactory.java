package com.javasleuth.core.command;

/**
 * CommandProcessor composition facade.
 *
 * <p>Default runtime services, command subsystem wiring, server lifecycle wiring, and owned
 * resource close order are delegated to focused package-level factories.</p>
 */
public final class CommandProcessorFactory {
    private CommandProcessorFactory() {}

    public static CommandProcessor createDefault(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer
    ) {
        return new CommandProcessor(createComponents(
            CommandProcessorFactoryRequest.builder(instrumentation, transformer).build()
        ));
    }

    public static CommandProcessor createDefault(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook
    ) {
        return new CommandProcessor(createComponents(
            CommandProcessorFactoryRequest.builder(instrumentation, transformer)
                .withShutdownHook(shutdownHook)
                .build()
        ));
    }

    public static CommandProcessor create(CommandProcessorFactoryRequest request) {
        return new CommandProcessor(createComponents(request));
    }

    public static CommandProcessorComponents createComponents(CommandProcessorFactoryRequest request) {
        RuntimeServices services = RuntimeServicesFactory.create(request);
        CommandSubsystem commandSubsystem = CommandSubsystemFactory.create(services);
        ServerSubsystem serverSubsystem = ServerSubsystemFactory.create(services, commandSubsystem);
        AutoCloseable ownedResources = ResourceCloser.forOwnedResources(services);
        logInitializedBestEffort(services.auditLogger, commandSubsystem.registry);
        return CommandProcessorComponents.from(services, commandSubsystem, serverSubsystem, ownedResources);
    }

    @Deprecated
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
        return create(CommandProcessorFactoryRequest.builder(instrumentation, transformer)
            .withShutdownHook(shutdownHook)
            .withConfig(config)
            .withAuditLogger(auditLogger)
            .withAuthenticationManager(authenticationManager)
            .withAuthorizationManager(authorizationManager)
            .withDangerousConfirm(dangerousConfirm)
            .withClientSessionRegistry(clientSessionRegistry)
            .withMetricsCollector(metricsCollector)
            .build());
    }

    @Deprecated
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
        return create(CommandProcessorFactoryRequest.builder(instrumentation, transformer)
            .withShutdownHook(shutdownHook)
            .withConfig(config)
            .withAuditLogger(auditLogger)
            .withAuthenticationManager(authenticationManager)
            .withAuthorizationManager(authorizationManager)
            .withDangerousConfirm(dangerousConfirm)
            .withClientSessionRegistry(clientSessionRegistry)
            .withMetricsCollector(metricsCollector)
            .withJobManager(jobManager)
            .withVmToolSessionRegistry(vmToolSessionRegistry)
            .build());
    }

    @Deprecated
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
        return create(CommandProcessorFactoryRequest.builder(instrumentation, transformer)
            .withShutdownHook(shutdownHook)
            .withConfig(config)
            .withAuditLogger(auditLogger)
            .withAuthenticationManager(authenticationManager)
            .withAuthorizationManager(authorizationManager)
            .withDangerousConfirm(dangerousConfirm)
            .withClientSessionRegistry(clientSessionRegistry)
            .withMetricsCollector(metricsCollector)
            .withJobManager(jobManager)
            .withVmToolSessionRegistry(vmToolSessionRegistry)
            .withPerformanceOptimizer(performanceOptimizer)
            .withSpyDispatcher(spyDispatcher)
            .build());
    }

    public static CommandProcessorComponents createComponents(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook
    ) {
        return createComponents(CommandProcessorFactoryRequest.builder(instrumentation, transformer)
            .withShutdownHook(shutdownHook)
            .build());
    }

    @Deprecated
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
        return createComponents(CommandProcessorFactoryRequest.builder(instrumentation, transformer)
            .withShutdownHook(shutdownHook)
            .withConfig(config)
            .withAuditLogger(auditLogger)
            .withAuthenticationManager(authenticationManager)
            .withAuthorizationManager(authorizationManager)
            .withDangerousConfirm(dangerousConfirm)
            .withClientSessionRegistry(clientSessionRegistry)
            .withMetricsCollector(metricsCollector)
            .build());
    }

    @Deprecated
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
        return createComponents(CommandProcessorFactoryRequest.builder(instrumentation, transformer)
            .withShutdownHook(shutdownHook)
            .withConfig(config)
            .withAuditLogger(auditLogger)
            .withAuthenticationManager(authenticationManager)
            .withAuthorizationManager(authorizationManager)
            .withDangerousConfirm(dangerousConfirm)
            .withClientSessionRegistry(clientSessionRegistry)
            .withMetricsCollector(metricsCollector)
            .withJobManager(jobManager)
            .withVmToolSessionRegistry(vmToolSessionRegistry)
            .build());
    }

    @Deprecated
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
            performanceOptimizer,
            spyDispatcher,
            null
        );
    }

    @Deprecated
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
        com.javasleuth.core.spy.SleuthSpyDispatcher spyDispatcher,
        com.javasleuth.core.enhancement.session.EnhancementSessionRegistry enhancementSessionRegistry
    ) {
        return createComponents(CommandProcessorFactoryRequest.builder(instrumentation, transformer)
            .withShutdownHook(shutdownHook)
            .withConfig(config)
            .withAuditLogger(auditLogger)
            .withAuthenticationManager(authenticationManager)
            .withAuthorizationManager(authorizationManager)
            .withDangerousConfirm(dangerousConfirm)
            .withClientSessionRegistry(clientSessionRegistry)
            .withMetricsCollector(metricsCollector)
            .withJobManager(jobManager)
            .withVmToolSessionRegistry(vmToolSessionRegistry)
            .withPerformanceOptimizer(performanceOptimizer)
            .withSpyDispatcher(spyDispatcher)
            .withEnhancementSessionRegistry(enhancementSessionRegistry)
            .build());
    }

    private static void logInitializedBestEffort(
        com.javasleuth.foundation.security.AuditLogger auditLogger,
        CommandRegistry registry
    ) {
        try {
            auditLogger.logSystemEvent(
                "COMMAND_PROCESSOR_INIT",
                "Command processor initialized with " + registry.getCommandMap().size() + " commands"
            );
        } catch (Exception ignore) {
            // ignore
        }
    }
}
