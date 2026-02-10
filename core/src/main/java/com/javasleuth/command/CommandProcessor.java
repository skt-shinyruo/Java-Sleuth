package com.javasleuth.command;

import com.javasleuth.command.protocol.Utf8LineCodec;
import com.javasleuth.command.server.CommandClientHandler;
import com.javasleuth.command.session.ClientSessionRegistry;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.security.AuditLogger;
import com.javasleuth.security.AuthenticationManager;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.InputValidator;
import com.javasleuth.security.RequestSecurityManager;
import com.javasleuth.util.PerformanceOptimizer;
import com.javasleuth.util.SleuthLogger;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class CommandProcessor {
    private static final SecureRandom SECRET_RANDOM = new SecureRandom();
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
    private final CommandRegistry registry;
    private final CommandPipeline pipeline;
    private final CommandClientHandler clientHandler;
    private final ConcurrentHashMap<String, String> sessionByClient = new ConcurrentHashMap<>();
    private final ClientSessionRegistry clientSessionRegistry = ClientSessionRegistry.getInstance();
    private ServerSocket serverSocket;

    public CommandProcessor(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this(instrumentation, transformer, null);
    }

    public CommandProcessor(Instrumentation instrumentation, SleuthClassFileTransformer transformer, Runnable shutdownHook) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.shutdownHook = shutdownHook;
        this.config = ProductionConfig.getInstance();
        try {
            final ProductionConfig cfg = this.config;
            SleuthLogger.setConfigProvider(new SleuthLogger.ConfigProvider() {
                @Override
                public String getString(String key, String defaultValue) {
                    return cfg.getString(key, defaultValue);
                }

                @Override
                public boolean getBoolean(String key, boolean defaultValue) {
                    return cfg.getBoolean(key, defaultValue);
                }

                @Override
                public boolean isLoading() {
                    return ProductionConfig.isLoading();
                }
            });
        } catch (Exception ignore) {
            // 忽略
        }

        // Configure JobManager retention from config (best-effort).
        try {
            JobManager.getInstance().configureRetention(
                this.config.getJobsMax(),
                this.config.getJobsTtlMs(),
                this.config.getJobsOutputMaxBytes()
            );
            JobManager.getInstance().configureExecution(
                this.config.getJobsMaxRunning(),
                this.config.getJobsQueueCapacity()
            );
        } catch (Exception e) {
            SleuthLogger.debug("JobManager config skipped (best-effort): " + e.getMessage(), e);
        }
        this.auditLogger = AuditLogger.getInstance();
        this.inputValidator = new InputValidator();
        this.authenticationManager = AuthenticationManager.getInstance();
        this.authorizationManager = AuthorizationManager.getInstance();
        this.requestSecurityManager = RequestSecurityManager.getInstance();

        this.clientExecutor = new ThreadPoolExecutor(
            config.getThreadPoolCoreSize(),
            config.getThreadPoolMaxSize(),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(config.getServerExecutorQueueCapacity()),
            r -> {
                Thread t = new Thread(r, "sleuth-client-" + commandCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.metricsCollector = new MetricsCollector();
        // Expose audit drop count via metrics/health.
        try {
            this.metricsCollector.recordAuditDropped(auditLogger.getDroppedCount());
        } catch (Exception e) {
            SleuthLogger.debug("Failed to record initial audit dropped count: " + e.getMessage(), e);
        }
        this.registry = new CommandRegistry(instrumentation, transformer, metricsCollector, config, auditLogger, shutdownHook);
        this.pipeline = new CommandPipeline(inputValidator, authorizationManager, config);
        this.clientHandler = new CommandClientHandler(
            running,
            commandCounter,
            metricsCollector,
            config,
            auditLogger,
            authenticationManager,
            requestSecurityManager,
            registry,
            pipeline,
            sessionByClient,
            clientSessionRegistry
        );

        auditLogger.logSystemEvent("COMMAND_PROCESSOR_INIT", "Command processor initialized with " + registry.getCommandMap().size() + " commands");
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            SleuthLogger.warn("Command processor is already running");
            return;
        }

        try {
            int port = config.getServerPort();
            String bindAddress = config.getServerBindAddress();

            if (!isLoopbackBind(bindAddress) && "off".equalsIgnoreCase(config.getSecurityMode())) {
                SleuthLogger.error("❌ SECURITY ERROR: Refusing to start with non-loopback bind and security.mode=off");
                SleuthLogger.error("Fix: set security.mode=hmac + security.hmac.secret, or bind to 127.0.0.1/::1");
                auditLogger.logSystemEvent("SERVER_START_BLOCKED",
                    "Refused to start: security.mode=off with non-loopback bind=" + bindAddress);
                running.set(false);
                serverSocket = null;
                return;
            }

            if ("hmac".equalsIgnoreCase(config.getSecurityMode())) {
                String secret = config.getSecurityHmacSecret();
                if (secret == null || secret.trim().isEmpty()) {
                    if (isLoopbackBind(bindAddress) && config.isHmacSecretAutogenOnLoopbackEnabled()) {
                        int bytes = config.getHmacBootstrapSecretBytes();
                        String generated = generateHmacSecret(bytes);
                        config.setRuntimeConfig("security.hmac.secret", generated);
                        auditLogger.logSystemEvent("HMAC_SECRET_AUTOGEN",
                            "Generated temporary HMAC secret for loopback bind=" + bindAddress);
                        if (config.isHmacSecretAutogenPrintEnabled()) {
                            SleuthLogger.warn("⚠️ SECURITY NOTICE: security.mode=hmac but security.hmac.secret is empty.");
                            SleuthLogger.warn("Generated a temporary HMAC secret for loopback-only listener.");
                            SleuthLogger.warn("To persist, set security.hmac.secret in your config file.");
                            if (System.console() != null) {
                                SleuthLogger.warn("Temporary security.hmac.secret = " + generated);
                            } else {
                                SleuthLogger.warn("Temporary security.hmac.secret was generated but NOT printed (no interactive console).");
                                SleuthLogger.warn("Fix: set security.hmac.secret explicitly in config, or run in interactive console to print it.");
                            }
                        }
                    } else {
                        SleuthLogger.error("❌ SECURITY ERROR: Refusing to start with security.mode=hmac but empty security.hmac.secret");
                        SleuthLogger.error("Fix: set security.hmac.secret, or bind to 127.0.0.1/::1 to enable autogen");
                        auditLogger.logSystemEvent("SERVER_START_BLOCKED",
                            "Refused to start: security.mode=hmac but security.hmac.secret is empty");
                        running.set(false);
                        serverSocket = null;
                        return;
                    }
                }
            }

            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(bindAddress, port));
            serverSocket.setSoTimeout(config.getSocketTimeout());
            SleuthLogger.info("🚀 Java-Sleuth listening on " + bindAddress + ":" + port);
            metricsCollector.recordServerStartup();
            auditLogger.logSystemEvent("SERVER_START", "Server started on port " + port);

            // Add shutdown hook for graceful termination
            addShutdownHook();

            // Main server loop
            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(config.getConnectionTimeout());
                    int maxConnections = config.getMaxConnections();
                    int active = metricsCollector.getActiveConnections();
                    if (maxConnections > 0 && active >= maxConnections) {
                        String remote = String.valueOf(clientSocket.getRemoteSocketAddress());
                        auditLogger.logSecurityViolation(null, remote, "MAX_CONNECTIONS",
                            "Rejected connection: active=" + active + ", max=" + maxConnections);
                        try {
                            BufferedOutputStream out = new BufferedOutputStream(clientSocket.getOutputStream());
                            Utf8LineCodec.writeLine(out, "ERROR: too many connections (max=" + maxConnections + ")", true);
                        } catch (Exception ignore) {
                            // ignore
                        } finally {
                            try {
                                clientSocket.close();
                            } catch (Exception ignore) {
                                // ignore
                            }
                        }
                        continue;
                    }

                    if (isClientExecutorSaturated()) {
                        String remote = String.valueOf(clientSocket.getRemoteSocketAddress());
                        auditLogger.logSystemEvent("SERVER_OVERLOADED",
                            "Rejected connection due to executor saturation: remote=" + remote);
                        metricsCollector.recordError("server_overload");
                        try {
                            BufferedOutputStream out = new BufferedOutputStream(clientSocket.getOutputStream());
                            Utf8LineCodec.writeLine(out, "ERROR: server busy (executor queue full)", true);
                        } catch (Exception ignore) {
                            // ignore
                        } finally {
                            try {
                                clientSocket.close();
                            } catch (Exception ignore) {
                                // ignore
                            }
                        }
                        continue;
                    }

                    try {
                        clientExecutor.execute(() -> clientHandler.handle(clientSocket));
                        metricsCollector.recordClientConnection();
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        String remote = String.valueOf(clientSocket.getRemoteSocketAddress());
                        auditLogger.logSystemEvent("SERVER_OVERLOADED",
                            "Rejected connection due to executor rejection: remote=" + remote);
                        metricsCollector.recordError("server_overload");
                        try {
                            BufferedOutputStream out = new BufferedOutputStream(clientSocket.getOutputStream());
                            Utf8LineCodec.writeLine(out, "ERROR: server busy (executor rejected)", true);
                        } catch (Exception ignore) {
                            // ignore
                        } finally {
                            try {
                                clientSocket.close();
                            } catch (Exception ignore) {
                                // ignore
                            }
                        }
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // Normal timeout, continue
                } catch (java.net.SocketException e) {
                    // Socket closed during shutdown - normal
                    if (running.get()) {
                        SleuthLogger.warn("Socket error: " + e.getMessage(), e);
                    }
                    break;
                } catch (IOException e) {
                    if (running.get()) {
                        SleuthLogger.warn("Error accepting client connection: " + e.getMessage(), e);
                        metricsCollector.recordError("connection_error");

                        // If we can't accept connections, wait a bit before retrying
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            SleuthLogger.error("❌ Failed to start command processor: " + e.getMessage(), e);
            auditLogger.logSystemEvent("SERVER_START_FAILED", "Failed to start server: " + e.getMessage());
            running.set(false);
        }

        SleuthLogger.info("ℹ️ Command processor main loop ended");
    }

    private boolean isClientExecutorSaturated() {
        try {
            if (clientExecutor == null) {
                return false;
            }
            int active = clientExecutor.getActiveCount();
            int max = clientExecutor.getMaximumPoolSize();
            if (max <= 0) {
                return false;
            }
            int remaining = clientExecutor.getQueue() != null ? clientExecutor.getQueue().remainingCapacity() : Integer.MAX_VALUE;
            return active >= max && remaining <= 0;
        } catch (Exception e) {
            SleuthLogger.debug("Failed to check executor saturation (best-effort): " + e.getMessage(), e);
            return false;
        }
    }

    private static boolean isLoopbackBind(String bindAddress) {
        if (bindAddress == null) {
            return true;
        }
        String v = bindAddress.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return true;
        }
        return "127.0.0.1".equals(v) || "localhost".equals(v) || "::1".equals(v);
    }

    private static String generateHmacSecret(int bytes) {
        int size = bytes <= 0 ? 32 : Math.min(bytes, 128);
        byte[] buf = new byte[size];
        SECRET_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public void shutdown() {
        shutdownGracefully(30); // 30 second timeout
    }

    /**
     * Graceful shutdown with configurable timeout
     */
    public void shutdownGracefully(int timeoutSeconds) {
        SleuthLogger.info("🔄 Initiating graceful shutdown (timeout: " + timeoutSeconds + "s)...");
        long shutdownStart = System.currentTimeMillis();
        auditLogger.logSystemEvent("SHUTDOWN_INITIATED", "Graceful shutdown started with " + timeoutSeconds + "s timeout");

        // Stop accepting new connections
        running.set(false);

        // 1. Close server socket to stop accepting new connections
        SleuthLogger.debug("Step 1/6: Closing server socket...");
        if (serverSocket != null) {
            try {
                serverSocket.close();
                SleuthLogger.debug("✅ Server socket closed");
            } catch (IOException e) {
                SleuthLogger.warn("⚠️ Error closing server socket: " + e.getMessage(), e);
            }
        }

        // 2. Wait for active connections to complete
        SleuthLogger.debug("Step 2/6: Waiting for active connections to complete...");
        int activeConnections = metricsCollector.getActiveConnections();
        if (activeConnections > 0) {
            SleuthLogger.debug("Waiting for " + activeConnections + " active connections to finish...");
            long waitStart = System.currentTimeMillis();
            while (metricsCollector.getActiveConnections() > 0 &&
                   (System.currentTimeMillis() - waitStart) < (timeoutSeconds * 1000 / 2)) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        SleuthLogger.debug("✅ Active connections handled");

        // 3. Shutdown client executor gracefully
        SleuthLogger.debug("Step 3/6: Shutting down client executor...");
        clientExecutor.shutdown();
        try {
            if (!clientExecutor.awaitTermination(timeoutSeconds / 3, TimeUnit.SECONDS)) {
                SleuthLogger.warn("⚠️ Client executor did not terminate gracefully, forcing shutdown...");
                clientExecutor.shutdownNow();
                if (!clientExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    SleuthLogger.error("❌ Client executor did not terminate after force shutdown");
                } else {
                    SleuthLogger.debug("✅ Client executor force shutdown complete");
                }
            } else {
                SleuthLogger.debug("✅ Client executor shutdown gracefully");
            }
        } catch (InterruptedException e) {
            clientExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Release plugin classloader resources (important on Windows to avoid JAR locks)
        try {
            registry.shutdown();
        } catch (Exception ignore) {
            // ignore
        }

        // 4. Shutdown performance optimizer
        SleuthLogger.debug("Step 4/6: Shutting down performance optimizer...");
        PerformanceOptimizer.shutdown();
        SleuthLogger.debug("✅ Performance optimizer shutdown complete");

        // 5. Record shutdown in metrics and shutdown metrics collector
        SleuthLogger.debug("Step 5/6: Finalizing metrics collection...");
        metricsCollector.recordServerShutdown();
        metricsCollector.shutdown();
        SleuthLogger.debug("✅ Metrics collection finalized");

        // 6. Shutdown audit logger (do this last to capture all events)
        SleuthLogger.debug("Step 6/6: Shutting down audit logger...");
        long shutdownDuration = System.currentTimeMillis() - shutdownStart;
        auditLogger.logSystemEvent("SHUTDOWN_COMPLETE", "Graceful shutdown completed in " + shutdownDuration + "ms");
        auditLogger.shutdown();
        SleuthLogger.debug("✅ Audit logger shutdown complete");

        SleuthLogger.info("🎉 Command processor shutdown complete in " + shutdownDuration + "ms");
    }

    /**
     * Emergency shutdown - immediate termination
     */
    public void emergencyShutdown() {
        SleuthLogger.error("🚨 EMERGENCY SHUTDOWN INITIATED");
        auditLogger.logSystemEvent("EMERGENCY_SHUTDOWN", "Emergency shutdown initiated");

        running.set(false);

        // Force close everything immediately
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception e) {
                // Ignore
            }
        }

        clientExecutor.shutdownNow();
        try {
            registry.shutdown();
        } catch (Exception ignore) {
            // ignore
        }
        PerformanceOptimizer.shutdown();
        metricsCollector.shutdown();
        auditLogger.shutdown();

        SleuthLogger.error("🚨 Emergency shutdown complete");
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
        Thread restartThread = new Thread(() -> {
            try {
                Thread.sleep(1000); // Give system time to clean up
                SleuthLogger.info("🚀 Starting command processor...");
                start();
                SleuthLogger.info("✅ Command processor restart complete");
            } catch (Exception e) {
                SleuthLogger.error("❌ Failed to restart command processor: " + e.getMessage(), e);
                auditLogger.logSystemEvent("RESTART_FAILED", "Command processor restart failed: " + e.getMessage());
            }
        }, "sleuth-restart");
        restartThread.setDaemon(false);
        restartThread.start();
    }

    /**
     * Add shutdown hook for graceful termination
     */
    public void addShutdownHook() {
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
