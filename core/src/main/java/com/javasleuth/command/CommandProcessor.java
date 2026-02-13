package com.javasleuth.command;

import com.javasleuth.command.server.CommandClientHandler;
import com.javasleuth.command.server.ConnectionAcceptor;
import com.javasleuth.command.server.ServerBootstrapper;
import com.javasleuth.command.server.ShutdownCoordinator;
import com.javasleuth.command.session.ClientSessionRegistry;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.security.AuditLogger;
import com.javasleuth.security.AuthenticationManager;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.DangerousCommandConfirmationManager;
import com.javasleuth.security.InputValidator;
import com.javasleuth.security.RequestSecurityManager;
import com.javasleuth.util.SleuthThreadFactory;
import com.javasleuth.util.SleuthLogger;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.ServerSocket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class CommandProcessor {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final Runnable shutdownHook;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong commandCounter = new AtomicLong(0);
    private final ThreadPoolExecutor clientExecutor;
    private final MetricsCollector metricsCollector;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final InputValidator inputValidator;
    private final AuthenticationManager authenticationManager;
    private final AuthorizationManager authorizationManager;
    private final RequestSecurityManager requestSecurityManager;
    private final DangerousCommandConfirmationManager dangerousConfirm;
    private final CommandRegistry registry;
    private final CommandPipeline pipeline;
    private final CommandClientHandler clientHandler;
    private final ServerBootstrapper bootstrapper;
    private final ConnectionAcceptor acceptor;
    private final ShutdownCoordinator shutdownCoordinator;
    private final ConcurrentHashMap<String, String> sessionByClient = new ConcurrentHashMap<>();
    private final ClientSessionRegistry clientSessionRegistry;
    private ServerSocket serverSocket;
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

    public CommandProcessor(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this(instrumentation, transformer, null);
    }

    public CommandProcessor(Instrumentation instrumentation, SleuthClassFileTransformer transformer, Runnable shutdownHook) {
        this(
            instrumentation,
            transformer,
            shutdownHook,
            ProductionConfig.getInstance(),
            AuditLogger.getInstance(),
            AuthenticationManager.getInstance(),
            AuthorizationManager.getInstance(),
            RequestSecurityManager.getInstance(),
            DangerousCommandConfirmationManager.getInstance(),
            ClientSessionRegistry.getInstance(),
            new MetricsCollector()
        );
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
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.shutdownHook = shutdownHook;
        this.config = config != null ? config : ProductionConfig.getInstance();
        this.bootstrapper = new ServerBootstrapper();
        this.bootstrapper.configureLoggingProvider(this.config);
        this.bootstrapper.configureJobManager(this.config);

        this.auditLogger = auditLogger != null ? auditLogger : AuditLogger.getInstance();
        this.inputValidator = new InputValidator();
        this.authenticationManager = authenticationManager != null ? authenticationManager : AuthenticationManager.getInstance();
        this.authorizationManager = authorizationManager != null ? authorizationManager : AuthorizationManager.getInstance();
        this.requestSecurityManager = requestSecurityManager != null ? requestSecurityManager : RequestSecurityManager.getInstance();
        DangerousCommandConfirmationManager dc =
            dangerousConfirm != null ? dangerousConfirm : DangerousCommandConfirmationManager.getInstance();
        this.dangerousConfirm = dc;

        this.clientExecutor = new ThreadPoolExecutor(
            this.config.getThreadPoolCoreSize(),
            this.config.getThreadPoolMaxSize(),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(this.config.getServerExecutorQueueCapacity()),
            SleuthThreadFactory.daemon("sleuth-client"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.metricsCollector = metricsCollector != null ? metricsCollector : new MetricsCollector();
        // Expose audit drop count via metrics/health.
        try {
            this.metricsCollector.recordAuditDropped(this.auditLogger.getDroppedCount());
        } catch (Exception e) {
            SleuthLogger.debug("Failed to record initial audit dropped count: " + e.getMessage(), e);
        }
        this.registry = new CommandRegistry(
            instrumentation,
            transformer,
            this.metricsCollector,
            this.config,
            this.auditLogger,
            shutdownHook,
            this.authenticationManager,
            dc
        );
        this.pipeline = new CommandPipeline(inputValidator, this.authorizationManager, dc, this.config);
        this.clientSessionRegistry = clientSessionRegistry != null ? clientSessionRegistry : ClientSessionRegistry.getInstance();
        this.clientHandler = new CommandClientHandler(
            running,
            commandCounter,
            this.metricsCollector,
            this.config,
            this.auditLogger,
            this.authenticationManager,
            this.requestSecurityManager,
            registry,
            pipeline,
            sessionByClient,
            this.clientSessionRegistry
        );
        this.acceptor = new ConnectionAcceptor();
        this.shutdownCoordinator = new ShutdownCoordinator(
            running,
            clientExecutor,
            this.metricsCollector,
            this.auditLogger,
            registry,
            pipeline,
            this.authenticationManager,
            this.authorizationManager,
            this.requestSecurityManager,
            this.dangerousConfirm
        );

        this.auditLogger.logSystemEvent("COMMAND_PROCESSOR_INIT", "Command processor initialized with " + registry.getCommandMap().size() + " commands");
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
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SleuthLogger.warn("⚠️ JVM shutdown detected - initiating graceful shutdown...");
            shutdownGracefully(10); // Shorter timeout for JVM shutdown
        }, "sleuth-shutdown-hook"));
        SleuthLogger.debug("✅ Shutdown hook registered");
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
