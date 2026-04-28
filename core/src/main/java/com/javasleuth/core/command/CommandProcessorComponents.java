package com.javasleuth.core.command;

/**
 * CommandProcessor 的组件集合（由组合根/工厂装配）。
 *
 * <p>该对象用于将“依赖装配 + 全局副作用”从 CommandProcessor 构造阶段剥离，令其更接近薄门面。</p>
 */
public final class CommandProcessorComponents {
    private final java.lang.instrument.Instrumentation instrumentation;
    private final com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer;
    private final Runnable shutdownHook;

    private final java.util.concurrent.atomic.AtomicBoolean running;
    private final java.util.concurrent.atomic.AtomicLong commandCounter;

    private final java.util.concurrent.ThreadPoolExecutor clientExecutor;
    private final com.javasleuth.core.monitoring.MetricsCollector metricsCollector;
    private final com.javasleuth.foundation.config.ProductionConfig config;
    private final com.javasleuth.foundation.security.AuditLogger auditLogger;

    private final com.javasleuth.foundation.security.InputValidator inputValidator;
    private final com.javasleuth.foundation.security.AuthenticationManager authenticationManager;
    private final com.javasleuth.foundation.security.AuthorizationManager authorizationManager;
    private final com.javasleuth.foundation.security.DangerousCommandConfirmationManager dangerousConfirm;

    private final com.javasleuth.core.command.session.ClientSessionRegistry clientSessionRegistry;
    private final com.javasleuth.core.command.session.ClientSessionIndex sessionIndex;
    private final com.javasleuth.core.enhancement.session.EnhancementSessionRegistry enhancementSessionRegistry;

    private final CommandRegistry registry;
    private final CommandPipeline pipeline;

    private final com.javasleuth.core.command.server.CommandClientHandler clientHandler;
    private final com.javasleuth.core.command.server.ServerBootstrapper bootstrapper;
    private final com.javasleuth.core.command.server.ConnectionAcceptor acceptor;
    private final com.javasleuth.core.command.server.ShutdownCoordinator shutdownCoordinator;
    private final AutoCloseable ownedResources;

    CommandProcessorComponents(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.core.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        java.util.concurrent.atomic.AtomicBoolean running,
        java.util.concurrent.atomic.AtomicLong commandCounter,
        java.util.concurrent.ThreadPoolExecutor clientExecutor,
        com.javasleuth.core.monitoring.MetricsCollector metricsCollector,
        com.javasleuth.foundation.config.ProductionConfig config,
        com.javasleuth.foundation.security.AuditLogger auditLogger,
        com.javasleuth.foundation.security.InputValidator inputValidator,
        com.javasleuth.foundation.security.AuthenticationManager authenticationManager,
        com.javasleuth.foundation.security.AuthorizationManager authorizationManager,
        com.javasleuth.foundation.security.DangerousCommandConfirmationManager dangerousConfirm,
        com.javasleuth.core.command.session.ClientSessionRegistry clientSessionRegistry,
        com.javasleuth.core.command.session.ClientSessionIndex sessionIndex,
        com.javasleuth.core.enhancement.session.EnhancementSessionRegistry enhancementSessionRegistry,
        CommandRegistry registry,
        CommandPipeline pipeline,
        com.javasleuth.core.command.server.CommandClientHandler clientHandler,
        com.javasleuth.core.command.server.ServerBootstrapper bootstrapper,
        com.javasleuth.core.command.server.ConnectionAcceptor acceptor,
        com.javasleuth.core.command.server.ShutdownCoordinator shutdownCoordinator,
        AutoCloseable ownedResources
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.shutdownHook = shutdownHook;
        this.running = running;
        this.commandCounter = commandCounter;
        this.clientExecutor = clientExecutor;
        this.metricsCollector = metricsCollector;
        this.config = config;
        this.auditLogger = auditLogger;
        this.inputValidator = inputValidator;
        this.authenticationManager = authenticationManager;
        this.authorizationManager = authorizationManager;
        this.dangerousConfirm = dangerousConfirm;
        this.clientSessionRegistry = clientSessionRegistry;
        this.sessionIndex = sessionIndex;
        this.enhancementSessionRegistry = enhancementSessionRegistry;
        this.registry = registry;
        this.pipeline = pipeline;
        this.clientHandler = clientHandler;
        this.bootstrapper = bootstrapper;
        this.acceptor = acceptor;
        this.shutdownCoordinator = shutdownCoordinator;
        this.ownedResources = ownedResources;
    }

    public java.lang.instrument.Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public com.javasleuth.core.enhancement.SleuthClassFileTransformer getTransformer() {
        return transformer;
    }

    public Runnable getShutdownHook() {
        return shutdownHook;
    }

    public java.util.concurrent.atomic.AtomicBoolean getRunning() {
        return running;
    }

    public java.util.concurrent.atomic.AtomicLong getCommandCounter() {
        return commandCounter;
    }

    public java.util.concurrent.ThreadPoolExecutor getClientExecutor() {
        return clientExecutor;
    }

    public com.javasleuth.core.monitoring.MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public com.javasleuth.foundation.config.ProductionConfig getConfig() {
        return config;
    }

    public com.javasleuth.foundation.security.AuditLogger getAuditLogger() {
        return auditLogger;
    }

    public com.javasleuth.foundation.security.InputValidator getInputValidator() {
        return inputValidator;
    }

    public com.javasleuth.foundation.security.AuthenticationManager getAuthenticationManager() {
        return authenticationManager;
    }

    public com.javasleuth.foundation.security.AuthorizationManager getAuthorizationManager() {
        return authorizationManager;
    }

    public com.javasleuth.foundation.security.DangerousCommandConfirmationManager getDangerousConfirm() {
        return dangerousConfirm;
    }

    public com.javasleuth.core.command.session.ClientSessionRegistry getClientSessionRegistry() {
        return clientSessionRegistry;
    }

    public com.javasleuth.core.command.session.ClientSessionIndex getSessionIndex() {
        return sessionIndex;
    }

    public com.javasleuth.core.enhancement.session.EnhancementSessionRegistry getEnhancementSessionRegistry() {
        return enhancementSessionRegistry;
    }

    public CommandRegistry getRegistry() {
        return registry;
    }

    public CommandPipeline getPipeline() {
        return pipeline;
    }

    public com.javasleuth.core.command.server.CommandClientHandler getClientHandler() {
        return clientHandler;
    }

    public com.javasleuth.core.command.server.ServerBootstrapper getBootstrapper() {
        return bootstrapper;
    }

    public com.javasleuth.core.command.server.ConnectionAcceptor getAcceptor() {
        return acceptor;
    }

    public com.javasleuth.core.command.server.ShutdownCoordinator getShutdownCoordinator() {
        return shutdownCoordinator;
    }

    public AutoCloseable getOwnedResources() {
        return ownedResources;
    }
}
