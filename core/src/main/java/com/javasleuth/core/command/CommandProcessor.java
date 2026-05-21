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
import com.javasleuth.foundation.util.SleuthThreadFactory;
import com.javasleuth.foundation.util.SleuthLogger;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
    private final AutoCloseable ownedResources;
    private ServerSocket serverSocket;
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);
    private final Object startupMonitor = new Object();
    private StartupSignal startupSignal = new StartupSignal();
    private volatile Thread jvmShutdownHook;

    public CommandProcessor(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this(CommandProcessorFactory.createComponents(
            CommandProcessorFactoryRequest.builder(instrumentation, transformer).build()
        ));
    }

    public CommandProcessor(Instrumentation instrumentation, SleuthClassFileTransformer transformer, Runnable shutdownHook) {
        this(CommandProcessorFactory.createComponents(
            CommandProcessorFactoryRequest.builder(instrumentation, transformer)
                .withShutdownHook(shutdownHook)
                .build()
        ));
    }

    /**
     * @deprecated Use {@link CommandProcessorFactoryRequest} with {@link CommandProcessorFactory#create(CommandProcessorFactoryRequest)}
     * or {@link CommandProcessorFactory#createComponents(CommandProcessorFactoryRequest)}.
     */
    @Deprecated
    public CommandProcessor(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        Runnable shutdownHook,
        ProductionConfig config,
        AuditLogger auditLogger,
        AuthenticationManager authenticationManager,
        AuthorizationManager authorizationManager,
        DangerousCommandConfirmationManager dangerousConfirm,
        ClientSessionRegistry clientSessionRegistry,
        MetricsCollector metricsCollector
    ) {
        this(CommandProcessorFactory.createComponents(
            CommandProcessorFactoryRequest.builder(instrumentation, transformer)
                .withShutdownHook(shutdownHook)
                .withConfig(config)
                .withAuditLogger(auditLogger)
                .withAuthenticationManager(authenticationManager)
                .withAuthorizationManager(authorizationManager)
                .withDangerousConfirm(dangerousConfirm)
                .withClientSessionRegistry(clientSessionRegistry)
                .withMetricsCollector(metricsCollector)
                .build()
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
        this.ownedResources = components.getOwnedResources();
    }

    public void prepareStartupSignal() {
        synchronized (startupMonitor) {
            startupSignal = new StartupSignal();
        }
    }

    public void awaitStartupOrThrow(long timeoutMillis) {
        StartupSignal signal;
        synchronized (startupMonitor) {
            signal = startupSignal;
        }
        signal.awaitOrThrow(timeoutMillis);
    }

    public void start() {
        StartupSignal startup = startupSignalForStart();
        if (!running.compareAndSet(false, true)) {
            SleuthLogger.warn("Command processor is already running");
            startup.success();
            return;
        }

        try {
            serverSocket = bootstrapper.bindAndValidate(running, config, auditLogger, metricsCollector);
            if (serverSocket == null) {
                serverSocket = null;
                startup.failure(new IllegalStateException("command processor bind was rejected"));
                return;
            }

            // Add shutdown hook for graceful termination
            addShutdownHook();
            startup.success();

            acceptor.acceptLoop(running, serverSocket, clientExecutor, clientHandler, config, auditLogger, metricsCollector);
        } catch (IOException e) {
            SleuthLogger.error("❌ Failed to start command processor: " + e.getMessage(), e);
            auditLogger.logSystemEvent("SERVER_START_FAILED", "Failed to start server: " + e.getMessage());
            running.set(false);
            startup.failure(e);
        } catch (RuntimeException e) {
            running.set(false);
            startup.failure(e);
            throw e;
        } catch (Error e) {
            running.set(false);
            startup.failure(e);
            throw e;
        }

        SleuthLogger.info("ℹ️ Command processor main loop ended");
    }

    private StartupSignal startupSignalForStart() {
        synchronized (startupMonitor) {
            if (startupSignal == null || startupSignal.isSignaled()) {
                startupSignal = new StartupSignal();
            }
            return startupSignal;
        }
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
        boolean executed = shutdownCoordinator.shutdownGracefully(serverSocket, timeoutSeconds);
        if (executed) {
            closeOwnedResourcesBestEffort();
        }
    }

    /**
     * Emergency shutdown - immediate termination
     */
    public void emergencyShutdown() {
        boolean executed = shutdownCoordinator.emergencyShutdown(serverSocket);
        if (executed) {
            closeOwnedResourcesBestEffort();
        }
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

    private void closeOwnedResourcesBestEffort() {
        AutoCloseable closeable = ownedResources;
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static final class StartupSignal {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicBoolean signaled = new AtomicBoolean(false);
        private volatile boolean success;
        private volatile Throwable failure;

        void success() {
            if (signaled.compareAndSet(false, true)) {
                success = true;
                latch.countDown();
            }
        }

        void failure(Throwable t) {
            if (signaled.compareAndSet(false, true)) {
                failure = t;
                latch.countDown();
            }
        }

        boolean isSignaled() {
            return signaled.get();
        }

        void awaitOrThrow(long timeoutMillis) {
            boolean completed;
            try {
                completed = latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("command processor startup interrupted", e);
            }
            if (!completed) {
                throw new IllegalStateException("command processor startup timed out after " + timeoutMillis + "ms");
            }
            Throwable startupFailure = failure;
            if (startupFailure != null) {
                throw new IllegalStateException("command processor failed to start: " + startupFailure.getMessage(), startupFailure);
            }
            if (!success) {
                throw new IllegalStateException("command processor startup did not complete successfully");
            }
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
