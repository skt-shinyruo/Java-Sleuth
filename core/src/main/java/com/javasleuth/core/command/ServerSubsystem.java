package com.javasleuth.core.command;

import com.javasleuth.core.command.server.CommandClientHandler;
import com.javasleuth.core.command.server.ConnectionAcceptor;
import com.javasleuth.core.command.server.ServerBootstrapper;
import com.javasleuth.core.command.server.ShutdownCoordinator;
import com.javasleuth.core.command.session.ClientSessionIndex;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Command server lifecycle assembly result.
 */
final class ServerSubsystem {
    final AtomicBoolean running;
    final AtomicLong commandCounter;
    final ThreadPoolExecutor clientExecutor;
    final ClientSessionIndex sessionIndex;
    final CommandClientHandler clientHandler;
    final ServerBootstrapper bootstrapper;
    final ConnectionAcceptor acceptor;
    final ShutdownCoordinator shutdownCoordinator;

    ServerSubsystem(
        AtomicBoolean running,
        AtomicLong commandCounter,
        ThreadPoolExecutor clientExecutor,
        ClientSessionIndex sessionIndex,
        CommandClientHandler clientHandler,
        ServerBootstrapper bootstrapper,
        ConnectionAcceptor acceptor,
        ShutdownCoordinator shutdownCoordinator
    ) {
        this.running = running;
        this.commandCounter = commandCounter;
        this.clientExecutor = clientExecutor;
        this.sessionIndex = sessionIndex;
        this.clientHandler = clientHandler;
        this.bootstrapper = bootstrapper;
        this.acceptor = acceptor;
        this.shutdownCoordinator = shutdownCoordinator;
    }
}
