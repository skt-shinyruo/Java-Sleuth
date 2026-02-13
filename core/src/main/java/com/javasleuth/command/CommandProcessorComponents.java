package com.javasleuth.command;

/**
 * CommandProcessor 的组件集合（由组合根/工厂装配）。
 *
 * <p>该对象用于将“依赖装配 + 全局副作用”从 CommandProcessor 构造阶段剥离，令其更接近薄门面。</p>
 */
public final class CommandProcessorComponents {
    private final java.lang.instrument.Instrumentation instrumentation;
    private final com.javasleuth.enhancement.SleuthClassFileTransformer transformer;
    private final Runnable shutdownHook;

    private final java.util.concurrent.atomic.AtomicBoolean running;
    private final java.util.concurrent.atomic.AtomicLong commandCounter;

    private final java.util.concurrent.ThreadPoolExecutor clientExecutor;
    private final com.javasleuth.monitoring.MetricsCollector metricsCollector;
    private final com.javasleuth.config.ProductionConfig config;
    private final com.javasleuth.security.AuditLogger auditLogger;

    private final com.javasleuth.security.InputValidator inputValidator;
    private final com.javasleuth.security.AuthenticationManager authenticationManager;
    private final com.javasleuth.security.AuthorizationManager authorizationManager;
    private final com.javasleuth.security.RequestSecurityManager requestSecurityManager;
    private final com.javasleuth.security.DangerousCommandConfirmationManager dangerousConfirm;

    private final com.javasleuth.command.session.ClientSessionRegistry clientSessionRegistry;
    private final com.javasleuth.command.session.ClientSessionIndex sessionIndex;

    private final CommandRegistry registry;
    private final CommandPipeline pipeline;

    private final com.javasleuth.command.server.CommandClientHandler clientHandler;
    private final com.javasleuth.command.server.ServerBootstrapper bootstrapper;
    private final com.javasleuth.command.server.ConnectionAcceptor acceptor;
    private final com.javasleuth.command.server.ShutdownCoordinator shutdownCoordinator;

    CommandProcessorComponents(
        java.lang.instrument.Instrumentation instrumentation,
        com.javasleuth.enhancement.SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        java.util.concurrent.atomic.AtomicBoolean running,
        java.util.concurrent.atomic.AtomicLong commandCounter,
        java.util.concurrent.ThreadPoolExecutor clientExecutor,
        com.javasleuth.monitoring.MetricsCollector metricsCollector,
        com.javasleuth.config.ProductionConfig config,
        com.javasleuth.security.AuditLogger auditLogger,
        com.javasleuth.security.InputValidator inputValidator,
        com.javasleuth.security.AuthenticationManager authenticationManager,
        com.javasleuth.security.AuthorizationManager authorizationManager,
        com.javasleuth.security.RequestSecurityManager requestSecurityManager,
        com.javasleuth.security.DangerousCommandConfirmationManager dangerousConfirm,
        com.javasleuth.command.session.ClientSessionRegistry clientSessionRegistry,
        com.javasleuth.command.session.ClientSessionIndex sessionIndex,
        CommandRegistry registry,
        CommandPipeline pipeline,
        com.javasleuth.command.server.CommandClientHandler clientHandler,
        com.javasleuth.command.server.ServerBootstrapper bootstrapper,
        com.javasleuth.command.server.ConnectionAcceptor acceptor,
        com.javasleuth.command.server.ShutdownCoordinator shutdownCoordinator
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
        this.requestSecurityManager = requestSecurityManager;
        this.dangerousConfirm = dangerousConfirm;
        this.clientSessionRegistry = clientSessionRegistry;
        this.sessionIndex = sessionIndex;
        this.registry = registry;
        this.pipeline = pipeline;
        this.clientHandler = clientHandler;
        this.bootstrapper = bootstrapper;
        this.acceptor = acceptor;
        this.shutdownCoordinator = shutdownCoordinator;
    }

    public java.lang.instrument.Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public com.javasleuth.enhancement.SleuthClassFileTransformer getTransformer() {
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

    public com.javasleuth.monitoring.MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public com.javasleuth.config.ProductionConfig getConfig() {
        return config;
    }

    public com.javasleuth.security.AuditLogger getAuditLogger() {
        return auditLogger;
    }

    public com.javasleuth.security.InputValidator getInputValidator() {
        return inputValidator;
    }

    public com.javasleuth.security.AuthenticationManager getAuthenticationManager() {
        return authenticationManager;
    }

    public com.javasleuth.security.AuthorizationManager getAuthorizationManager() {
        return authorizationManager;
    }

    public com.javasleuth.security.RequestSecurityManager getRequestSecurityManager() {
        return requestSecurityManager;
    }

    public com.javasleuth.security.DangerousCommandConfirmationManager getDangerousConfirm() {
        return dangerousConfirm;
    }

    public com.javasleuth.command.session.ClientSessionRegistry getClientSessionRegistry() {
        return clientSessionRegistry;
    }

    public com.javasleuth.command.session.ClientSessionIndex getSessionIndex() {
        return sessionIndex;
    }

    public CommandRegistry getRegistry() {
        return registry;
    }

    public CommandPipeline getPipeline() {
        return pipeline;
    }

    public com.javasleuth.command.server.CommandClientHandler getClientHandler() {
        return clientHandler;
    }

    public com.javasleuth.command.server.ServerBootstrapper getBootstrapper() {
        return bootstrapper;
    }

    public com.javasleuth.command.server.ConnectionAcceptor getAcceptor() {
        return acceptor;
    }

    public com.javasleuth.command.server.ShutdownCoordinator getShutdownCoordinator() {
        return shutdownCoordinator;
    }
}

