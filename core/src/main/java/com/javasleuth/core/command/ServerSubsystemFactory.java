package com.javasleuth.core.command;

import com.javasleuth.core.command.server.CommandClientHandler;
import com.javasleuth.core.command.server.ConnectionAcceptor;
import com.javasleuth.core.command.server.ServerBootstrapper;
import com.javasleuth.core.command.server.ShutdownCoordinator;
import com.javasleuth.core.command.session.ClientSessionIndex;
import com.javasleuth.foundation.util.SleuthThreadFactory;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds command server lifecycle dependencies.
 */
final class ServerSubsystemFactory {
    private ServerSubsystemFactory() {}

    static ServerSubsystem create(RuntimeServices services, CommandSubsystem commandSubsystem) {
        if (services == null) {
            throw new IllegalArgumentException("services is required");
        }
        if (commandSubsystem == null) {
            throw new IllegalArgumentException("commandSubsystem is required");
        }

        ServerBootstrapper bootstrapper = new ServerBootstrapper();
        bootstrapper.configureLoggingProvider(services.config);
        bootstrapper.configureJobManager(services.jobManager, services.config);

        AtomicBoolean running = new AtomicBoolean(false);
        AtomicLong commandCounter = new AtomicLong(0);

        ThreadPoolExecutor clientExecutor = new ThreadPoolExecutor(
            services.typedConfig.performance().getThreadPoolCoreSize(),
            services.typedConfig.performance().getThreadPoolMaxSize(),
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(services.typedConfig.server().getExecutorQueueCapacity()),
            SleuthThreadFactory.daemon("sleuth-client"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        ClientSessionIndex sessionIndex = new ClientSessionIndex();

        CommandClientHandler clientHandler = new CommandClientHandler(
            running,
            commandCounter,
            services.metricsCollector,
            services.config,
            services.auditLogger,
            services.authenticationManager,
            commandSubsystem.registry,
            commandSubsystem.pipeline,
            sessionIndex,
            services.clientSessionRegistry
        );

        ConnectionAcceptor acceptor = new ConnectionAcceptor();

        ShutdownCoordinator shutdownCoordinator = new ShutdownCoordinator(
            running,
            clientExecutor,
            services.metricsCollector,
            services.auditLogger,
            commandSubsystem.registry,
            commandSubsystem.pipeline,
            services.authorizationManager
        );

        return new ServerSubsystem(
            running,
            commandCounter,
            clientExecutor,
            sessionIndex,
            clientHandler,
            bootstrapper,
            acceptor,
            shutdownCoordinator
        );
    }
}
