package com.javasleuth.command;

import com.javasleuth.command.impl.*;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.util.PerformanceOptimizer;
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.security.AuditLogger;
import com.javasleuth.security.InputValidator;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class CommandProcessor {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConcurrentHashMap<String, Command> commands;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong commandCounter = new AtomicLong(0);
    private final ExecutorService clientExecutor;
    private final MetricsCollector metricsCollector;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final InputValidator inputValidator;
    private ServerSocket serverSocket;

    public CommandProcessor(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.commands = new ConcurrentHashMap<>();
        this.config = ProductionConfig.getInstance();
        this.auditLogger = AuditLogger.getInstance();
        this.inputValidator = new InputValidator();

        this.clientExecutor = new ThreadPoolExecutor(
            config.getThreadPoolCoreSize(),
            config.getThreadPoolMaxSize(),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            r -> {
                Thread t = new Thread(r, "sleuth-client-" + commandCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.metricsCollector = new MetricsCollector();
        initializeCommands();

        auditLogger.logSystemEvent("COMMAND_PROCESSOR_INIT", "Command processor initialized with " + commands.size() + " commands");
    }

    private void initializeCommands() {
        // Existing commands
        commands.put("dashboard", new DashboardCommand(instrumentation));
        commands.put("thread", new ThreadCommand(instrumentation));
        commands.put("sc", new SearchClassCommand(instrumentation));
        commands.put("sm", new SearchMethodCommand(instrumentation));
        commands.put("watch", new WatchCommand(instrumentation, transformer));
        commands.put("trace", new TraceCommand(instrumentation, transformer));
        commands.put("redefine", new RedefineCommand(instrumentation));
        commands.put("mc", new MemoryCompilerCommand());
        commands.put("retransform", new RetransformCommand(instrumentation));

        // Phase 1 - Critical Performance Commands
        commands.put("profiler", new ProfilerCommand(instrumentation));
        commands.put("monitor", new MonitorCommand(instrumentation, transformer));
        commands.put("stack", new StackCommand(instrumentation));

        // Phase 4 - High Priority Commands
        commands.put("jvm", new JvmCommand(instrumentation));
        commands.put("sysprop", new SysPropCommand(instrumentation));
        commands.put("sysenv", new SysEnvCommand(instrumentation));
        commands.put("vmoption", new VmOptionCommand(instrumentation));
        commands.put("memory", new MemoryCommand(instrumentation));
        commands.put("heapdump", new HeapDumpCommand(instrumentation));

        // Phase 5 - Critical Production Commands
        commands.put("jad", new JadCommand(instrumentation));
        commands.put("classloader", new ClassLoaderCommand(instrumentation));
        commands.put("mbean", new MBeanCommand(instrumentation));

        // System commands
        commands.put("help", new HelpCommand(commands));
        commands.put("quit", new QuitCommand());

        // Production monitoring commands
        commands.put("health", new HealthCommand(metricsCollector));
        commands.put("metrics", new MetricsCommand(metricsCollector));
        commands.put("status", new StatusCommand(instrumentation, metricsCollector));
        commands.put("config", new ConfigCommand(config));
        commands.put("audit", new AuditCommand(auditLogger));
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            System.out.println("Command processor is already running");
            return;
        }

        try {
            int port = config.getServerPort();
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(config.getSocketTimeout());
            System.out.println("🚀 Java-Sleuth listening on port " + port);
            metricsCollector.recordServerStartup();
            auditLogger.logSystemEvent("SERVER_START", "Server started on port " + port);

            // Add shutdown hook for graceful termination
            addShutdownHook();

            // Main server loop
            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(config.getConnectionTimeout());
                    metricsCollector.recordClientConnection();

                    clientExecutor.submit(() -> handleClient(clientSocket));
                } catch (java.net.SocketTimeoutException e) {
                    // Normal timeout, continue
                } catch (java.net.SocketException e) {
                    // Socket closed during shutdown - normal
                    if (running.get()) {
                        System.err.println("Socket error: " + e.getMessage());
                    }
                    break;
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("Error accepting client connection: " + e.getMessage());
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
            System.err.println("❌ Failed to start command processor: " + e.getMessage());
            auditLogger.logSystemEvent("SERVER_START_FAILED", "Failed to start server: " + e.getMessage());
            running.set(false);
        }

        System.out.println("ℹ️ Command processor main loop ended");
    }

    private void handleClient(Socket clientSocket) {
        long sessionStart = System.currentTimeMillis();
        String clientId = "client-" + commandCounter.incrementAndGet();
        String clientInfo = clientSocket.getRemoteSocketAddress().toString();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {

            writer.println("Welcome to Java-Sleuth! Type 'help' for available commands.");
            metricsCollector.recordSessionStart(clientId);
            auditLogger.logConnectionEvent(clientId, clientInfo, "CONNECT");

            String line;
            while ((line = reader.readLine()) != null && running.get()) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                long commandStart = System.currentTimeMillis();
                String[] parts = line.split("\\s+");
                String commandName = parts[0].toLowerCase();

                // Enhanced input validation and security
                InputValidator.ValidationResult validation = inputValidator.validateCommand(clientId, clientInfo, commandName, parts);
                if (!validation.isValid()) {
                    writer.println("Security validation failed: " + validation.getMessage());
                    metricsCollector.recordError("validation_failed");
                    continue;
                }

                Command command = commands.get(commandName);
                if (command != null) {
                    boolean success = false;
                    try {
                        metricsCollector.recordCommandStart(commandName);

                        // Use caching for appropriate commands (not for dynamic data)
                        String cacheKey = shouldCache(commandName) ? commandName + ":" + String.join(":", parts) : null;
                        String result;

                        if (cacheKey != null) {
                            result = PerformanceOptimizer.getCachedResult(cacheKey, () -> {
                                try {
                                    return command.execute(parts);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
                        } else {
                            result = command.execute(parts);
                        }

                        // Sanitize output for security
                        InputValidator.ValidationResult outputValidation = inputValidator.sanitizeOutput(result);
                        String sanitizedResult = outputValidation.getSanitizedOutput() != null ? outputValidation.getSanitizedOutput() : result;

                        writer.println(sanitizedResult);
                        long duration = System.currentTimeMillis() - commandStart;
                        metricsCollector.recordCommandComplete(commandName, duration);
                        success = true;

                        // Log successful command execution
                        auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, true);

                        if ("quit".equals(commandName)) {
                            break;
                        }
                    } catch (Exception e) {
                        writer.println("Error executing command: " + e.getMessage());
                        metricsCollector.recordError("command_execution");
                        auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, false);
                    }
                } else {
                    writer.println("Unknown command: " + commandName + ". Type 'help' for available commands.");
                    metricsCollector.recordError("unknown_command");
                }
            }
        } catch (IOException e) {
            System.err.println("Error handling client " + clientId + ": " + e.getMessage());
            metricsCollector.recordError("client_io_error");
        } finally {
            try {
                clientSocket.close();
                long sessionDuration = System.currentTimeMillis() - sessionStart;
                metricsCollector.recordSessionEnd(clientId, sessionDuration);
                auditLogger.logConnectionEvent(clientId, clientInfo, "DISCONNECT");
                metricsCollector.recordClientDisconnection();
            } catch (IOException e) {
                // Ignore close errors
            }
        }
    }

    private boolean shouldCache(String commandName) {
        // Cache static information but not dynamic data
        switch (commandName.toLowerCase()) {
            case "help":
            case "sc": // Search class
            case "sm": // Search method
            case "jad": // Decompile
            case "classloader":
                return true;
            case "dashboard":
            case "thread":
            case "memory":
            case "jvm":
            case "health":
            case "metrics":
            case "status":
                return false; // Dynamic data shouldn't be cached
            default:
                return false;
        }
    }

    public void shutdown() {
        shutdownGracefully(30); // 30 second timeout
    }

    /**
     * Graceful shutdown with configurable timeout
     */
    public void shutdownGracefully(int timeoutSeconds) {
        System.out.println("🔄 Initiating graceful shutdown (timeout: " + timeoutSeconds + "s)...");
        long shutdownStart = System.currentTimeMillis();
        auditLogger.logSystemEvent("SHUTDOWN_INITIATED", "Graceful shutdown started with " + timeoutSeconds + "s timeout");

        // Stop accepting new connections
        running.set(false);

        // 1. Close server socket to stop accepting new connections
        System.out.println("Step 1/6: Closing server socket...");
        if (serverSocket != null) {
            try {
                serverSocket.close();
                System.out.println("✅ Server socket closed");
            } catch (IOException e) {
                System.err.println("⚠️ Error closing server socket: " + e.getMessage());
            }
        }

        // 2. Wait for active connections to complete
        System.out.println("Step 2/6: Waiting for active connections to complete...");
        int activeConnections = metricsCollector.getActiveConnections();
        if (activeConnections > 0) {
            System.out.println("Waiting for " + activeConnections + " active connections to finish...");
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
        System.out.println("✅ Active connections handled");

        // 3. Shutdown client executor gracefully
        System.out.println("Step 3/6: Shutting down client executor...");
        clientExecutor.shutdown();
        try {
            if (!clientExecutor.awaitTermination(timeoutSeconds / 3, TimeUnit.SECONDS)) {
                System.out.println("⚠️ Client executor did not terminate gracefully, forcing shutdown...");
                clientExecutor.shutdownNow();
                if (!clientExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("❌ Client executor did not terminate after force shutdown");
                } else {
                    System.out.println("✅ Client executor force shutdown complete");
                }
            } else {
                System.out.println("✅ Client executor shutdown gracefully");
            }
        } catch (InterruptedException e) {
            clientExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 4. Shutdown performance optimizer
        System.out.println("Step 4/6: Shutting down performance optimizer...");
        PerformanceOptimizer.shutdown();
        System.out.println("✅ Performance optimizer shutdown complete");

        // 5. Record shutdown in metrics and shutdown metrics collector
        System.out.println("Step 5/6: Finalizing metrics collection...");
        metricsCollector.recordServerShutdown();
        metricsCollector.shutdown();
        System.out.println("✅ Metrics collection finalized");

        // 6. Shutdown audit logger (do this last to capture all events)
        System.out.println("Step 6/6: Shutting down audit logger...");
        long shutdownDuration = System.currentTimeMillis() - shutdownStart;
        auditLogger.logSystemEvent("SHUTDOWN_COMPLETE", "Graceful shutdown completed in " + shutdownDuration + "ms");
        auditLogger.shutdown();
        System.out.println("✅ Audit logger shutdown complete");

        System.out.println("🎉 Command processor shutdown complete in " + shutdownDuration + "ms");
    }

    /**
     * Emergency shutdown - immediate termination
     */
    public void emergencyShutdown() {
        System.err.println("🚨 EMERGENCY SHUTDOWN INITIATED");
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
        PerformanceOptimizer.shutdown();
        metricsCollector.shutdown();
        auditLogger.shutdown();

        System.err.println("🚨 Emergency shutdown complete");
    }

    /**
     * Restart the command processor
     */
    public void restart() {
        System.out.println("🔄 Restarting command processor...");
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
                System.out.println("🚀 Starting command processor...");
                start();
                System.out.println("✅ Command processor restart complete");
            } catch (Exception e) {
                System.err.println("❌ Failed to restart command processor: " + e.getMessage());
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
            System.out.println("⚠️ JVM shutdown detected - initiating graceful shutdown...");
            shutdownGracefully(10); // Shorter timeout for JVM shutdown
        }, "sleuth-shutdown-hook"));
        System.out.println("✅ Shutdown hook registered");
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