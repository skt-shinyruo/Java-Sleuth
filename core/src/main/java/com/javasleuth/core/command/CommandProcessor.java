package com.javasleuth.core.command;

/**
 * 命令服务端生命周期门面（facade）。
 *
 * <p>该类仅保留 start/shutdown/restart 等稳定入口，依赖装配与全局副作用由 {@link CommandProcessorFactory} 负责。</p>
 */
import com.javasleuth.core.command.server.CommandClientHandler;
import com.javasleuth.core.command.server.ConnectionAcceptor;
import com.javasleuth.core.command.server.ServerBootstrapper;
import com.javasleuth.core.command.server.ShutdownCoordinator;
import com.javasleuth.core.command.session.ClientSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.RequestSecurityManager;
import com.javasleuth.foundation.util.SleuthThreadFactory;
import com.javasleuth.foundation.util.SleuthLogger;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.ServerSocket;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

public class CommandProcessor {
    private final AtomicBoolean running;
    private final ThreadPoolExecutor clientExecutor;
    private final MetricsCollector metricsCollector;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final CommandClientHandler clientHandler;
    private final ServerBootstrapper bootstrapper;
    private final ConnectionAcceptor acceptor;
    private final ShutdownCoordinator shutdownCoordinator;
    private ServerSocket serverSocket;
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);
    private volatile Thread jvmShutdownHook;

    public CommandProcessor(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this(CommandProcessorFactory.createComponents(instrumentation, transformer, null));
    }

    public CommandProcessor(Instrumentation instrumentation, SleuthClassFileTransformer transformer, Runnable shutdownHook) {
        this(CommandProcessorFactory.createComponents(instrumentation, transformer, shutdownHook));
    }

    public CommandProcessor(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        ProductionConfig config,
        AuditLogger auditLogger,
        AuthenticationManager authenticationManager,
        AuthorizationManager authorizationManager,
        RequestSecurityManager requestSecurityManager,
        DangerousCommandConfirmationManager dangerousConfirm,
        ClientSessionRegistry clientSessionRegistry,
        MetricsCollector metricsCollector
    ) {
        this(CommandProcessorFactory.createComponents(
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
        ));
    }

    public CommandProcessor(CommandProcessorComponents components) {
        if (components == null) {
            throw new IllegalArgumentException("components is required");
        }
        this.running = components.getRunning();
        this.clientExecutor = components.getClientExecutor();
        this.metricsCollector = components.getMetricsCollector();
        this.config = components.getConfig();
        this.auditLogger = components.getAuditLogger();
        this.clientHandler = components.getClientHandler();
        this.bootstrapper = components.getBootstrapper();
        this.acceptor = components.getAcceptor();
        this.shutdownCoordinator = components.getShutdownCoordinator();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            SleuthLogger.warn("Command processor is already running");
            return;
        }

        try {
            serverSocket = bootstrapper.bindAndValidate(running, config, auditLogger, metricsCollector);
            if (serverSocket == null) {
                serverSocket = null;
                return;
            }

            // Add shutdown hook for graceful termination
            addShutdownHook();

            acceptor.acceptLoop(running, serverSocket, clientExecutor, clientHandler, config, auditLogger, metricsCollector);
        } catch (IOException e) {
            SleuthLogger.error("❌ Failed to start command processor: " + e.getMessage(), e);
            auditLogger.logSystemEvent("SERVER_START_FAILED", "Failed to start server: " + e.getMessage());
            running.set(false);
        }

        SleuthLogger.info("ℹ️ Command processor main loop ended");
    }

    public void shutdown() {
        shutdownGracefully(30); // 30 second timeout
    }

    /**
     * Shutdown for detach/agent runtime close.
     *
     * <p>Differs from {@link #shutdown()} in that it also best-effort removes the JVM shutdown hook,
     * to avoid hook accumulation across detach → re-attach in the same JVM.</p>
     */
    public void shutdownForDetach() {
        shutdown();
        removeShutdownHook();
    }

    /**
     * Graceful shutdown with configurable timeout
     */
    public void shutdownGracefully(int timeoutSeconds) {
        shutdownCoordinator.shutdownGracefully(serverSocket, timeoutSeconds);
    }

    /**
     * Emergency shutdown - immediate termination
     */
    public void emergencyShutdown() {
        shutdownCoordinator.emergencyShutdown(serverSocket);
    }

    /**
     * Restart the command processor
     */
    public void restart() {
        SleuthLogger.info("🔄 Restarting command processor...");
        auditLogger.logSystemEvent("RESTART_INITIATED", "Command processor restart initiated");

        // Graceful shutdown first
        shutdownGracefully(15);

        // Small delay to ensure cleanup
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Restart in new thread
        Thread restartThread = SleuthThreadFactory.daemonFixed("sleuth-restart").newThread(() -> {
            try {
                Thread.sleep(1000); // Give system time to clean up
                SleuthLogger.info("🚀 Starting command processor...");
                start();
                SleuthLogger.info("✅ Command processor restart complete");
            } catch (Exception e) {
                SleuthLogger.error("❌ Failed to restart command processor: " + e.getMessage(), e);
                auditLogger.logSystemEvent("RESTART_FAILED", "Command processor restart failed: " + e.getMessage());
            }
        });
        restartThread.start();
    }

    /**
     * Add shutdown hook for graceful termination
     */
    public void addShutdownHook() {
        if (!shutdownHookRegistered.compareAndSet(false, true)) {
            return;
        }
        Thread hook = new Thread(() -> {
            SleuthLogger.warn("⚠️ JVM shutdown detected - initiating graceful shutdown...");
            shutdownGracefully(10); // Shorter timeout for JVM shutdown
        }, "sleuth-shutdown-hook");
        jvmShutdownHook = hook;
        Runtime.getRuntime().addShutdownHook(hook);
        SleuthLogger.debug("✅ Shutdown hook registered");
    }

    private void removeShutdownHook() {
        Thread hook = jvmShutdownHook;
        if (hook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignore) {
            // JVM is already shutting down.
        } catch (Exception ignore) {
            // best-effort
        } finally {
            jvmShutdownHook = null;
        }
    }

    /**
     * Get shutdown status
     */
    public String getShutdownStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== SHUTDOWN STATUS ===\n");
        status.append("Server Running: ").append(running.get() ? "YES" : "NO").append("\n");
        status.append("Server Socket Open: ").append(serverSocket != null && !serverSocket.isClosed() ? "YES" : "NO").append("\n");
        status.append("Client Executor Status: ").append(clientExecutor.isShutdown() ? "SHUTDOWN" : "RUNNING").append("\n");
        status.append("Client Executor Terminated: ").append(clientExecutor.isTerminated() ? "YES" : "NO").append("\n");
        status.append("Active Connections: ").append(metricsCollector.getActiveConnections()).append("\n");
        return status.toString();
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public ProductionConfig getConfig() {
        return config;
    }

    public AuditLogger getAuditLogger() {
        return auditLogger;
    }
}
