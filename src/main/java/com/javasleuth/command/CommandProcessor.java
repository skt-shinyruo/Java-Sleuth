package com.javasleuth.command;

import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.security.AuditLogger;
import com.javasleuth.security.AuthenticationManager;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.InputValidator;
import com.javasleuth.security.RequestSecurityManager;
import com.javasleuth.util.PerformanceOptimizer;
import com.javasleuth.command.protocol.BinaryFrame;
import com.javasleuth.command.protocol.BinaryFrameCodec;
import com.javasleuth.command.protocol.Frame;
import com.javasleuth.command.protocol.FrameCodec;
import com.javasleuth.command.protocol.Utf8LineCodec;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class CommandProcessor {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong commandCounter = new AtomicLong(0);
    private final ExecutorService clientExecutor;
    private final MetricsCollector metricsCollector;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final InputValidator inputValidator;
    private final AuthenticationManager authenticationManager;
    private final AuthorizationManager authorizationManager;
    private final RequestSecurityManager requestSecurityManager;
    private final CommandRegistry registry;
    private final CommandPipeline pipeline;
    private final ConcurrentHashMap<String, String> sessionByClient = new ConcurrentHashMap<>();
    private ServerSocket serverSocket;

    public CommandProcessor(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = ProductionConfig.getInstance();

        // Configure JobManager retention from config (best-effort).
        try {
            JobManager.getInstance().configureRetention(
                this.config.getJobsMax(),
                this.config.getJobsTtlMs(),
                this.config.getJobsOutputMaxBytes()
            );
        } catch (Exception ignore) {
            // ignore
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
            new LinkedBlockingQueue<>(50),
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
        } catch (Exception ignore) {
            // ignore
        }
        this.registry = new CommandRegistry(instrumentation, transformer, metricsCollector, config, auditLogger);
        this.pipeline = new CommandPipeline(inputValidator, authorizationManager, config);

        auditLogger.logSystemEvent("COMMAND_PROCESSOR_INIT", "Command processor initialized with " + registry.getCommandMap().size() + " commands");
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            System.out.println("Command processor is already running");
            return;
        }

        try {
            int port = config.getServerPort();
            serverSocket = new ServerSocket();
            String bindAddress = config.getServerBindAddress();
            serverSocket.bind(new InetSocketAddress(bindAddress, port));
            serverSocket.setSoTimeout(config.getSocketTimeout());
            System.out.println("🚀 Java-Sleuth listening on " + bindAddress + ":" + port);
            metricsCollector.recordServerStartup();
            auditLogger.logSystemEvent("SERVER_START", "Server started on port " + port);

            if (!isLoopbackBind(bindAddress) && "off".equalsIgnoreCase(config.getSecurityMode())) {
                System.err.println("❌ SECURITY ERROR: Refusing to start with non-loopback bind and security.mode=off");
                System.err.println("Fix: set security.mode=hmac + security.hmac.secret, or bind to 127.0.0.1/::1");
                auditLogger.logSystemEvent("SERVER_START_BLOCKED",
                    "Refused to start: security.mode=off with non-loopback bind=" + bindAddress);
                running.set(false);
                try {
                    serverSocket.close();
                } catch (Exception ignore) {
                    // ignore
                }
                return;
            }

            if ("hmac".equalsIgnoreCase(config.getSecurityMode())) {
                String secret = config.getSecurityHmacSecret();
                if (secret == null || secret.trim().isEmpty()) {
                    System.err.println("❌ SECURITY ERROR: Refusing to start with security.mode=hmac but empty security.hmac.secret");
                    auditLogger.logSystemEvent("SERVER_START_BLOCKED",
                        "Refused to start: security.mode=hmac but security.hmac.secret is empty");
                    running.set(false);
                    try {
                        serverSocket.close();
                    } catch (Exception ignore) {
                        // ignore
                    }
                    return;
                }
            }

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
        String sessionId = null;

        AuthenticationManager.UserRole initialRole = AuthenticationManager.UserRole.VIEWER;
        String securityMode = config.getSecurityMode();
        if ("hmac".equalsIgnoreCase(securityMode)) {
            initialRole = AuthenticationManager.UserRole.fromName(
                config.getHmacSessionRole(),
                AuthenticationManager.UserRole.OPERATOR
            );
        }

        AuthenticationManager.AuthenticationResult sessionResult =
            authenticationManager.createSession(initialRole, clientInfo);
        if (sessionResult.isSuccess()) {
            sessionId = sessionResult.getSessionId();
            sessionByClient.put(clientId, sessionId);
        }

        boolean pendingBinaryUpgrade = false;

        int maxLineBytes = config.getInt(
            "protocol.text.max.line.bytes",
            Math.max(8192, config.getFrameMaxPayload() * 2)
        );

        try (BufferedInputStream in = new BufferedInputStream(clientSocket.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(clientSocket.getOutputStream())) {

            Utf8LineCodec.writeLine(out, "Welcome to Java-Sleuth! Type 'help' for available commands.", true);
            metricsCollector.recordSessionStart(clientId);
            auditLogger.logConnectionEvent(clientId, clientInfo, "CONNECT");

            while (running.get()) {
                String line;
                try {
                    line = Utf8LineCodec.readLine(in, maxLineBytes);
                } catch (java.net.SocketTimeoutException timeout) {
                    continue;
                }
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (config.isHandshakeEnabled() && line.toUpperCase().startsWith("HELLO")) {
                    String selected = handleHello(line, out);
                    pendingBinaryUpgrade = "binary".equalsIgnoreCase(selected);
                    continue;
                }

                if (pendingBinaryUpgrade) {
                    if ("UPGRADE BINARY".equalsIgnoreCase(line)) {
                        Utf8LineCodec.writeLine(out, "OK", true);
                        metricsCollector.recordBinaryUpgrade();
                        handleBinaryClient(
                            new DataInputStream(in),
                            new DataOutputStream(out),
                            clientId,
                            clientInfo,
                            sessionId
                        );
                        break;
                    } else {
                        sendError(out, false, "Handshake pending. Send: UPGRADE BINARY");
                        continue;
                    }
                }

                long commandStart = System.currentTimeMillis();
                final boolean framedRequested;
                final boolean streamRequested;
                final String raw;
                if (line.startsWith("CMD ")) {
                    framedRequested = true;
                    streamRequested = false;
                    raw = line.substring(4);
                } else if (line.startsWith("STREAM ")) {
                    framedRequested = true;
                    streamRequested = true;
                    raw = line.substring(7);
                } else {
                    framedRequested = false;
                    streamRequested = false;
                    raw = line;
                }

                String verifyKey = sessionByClient.get(clientId);
                if (verifyKey == null) {
                    verifyKey = sessionId;
                }
                if (verifyKey == null) {
                    verifyKey = clientId;
                }
                RequestSecurityManager.VerificationResult verified = requestSecurityManager.verifyAndExtract(verifyKey, raw);
                if (!verified.isOk()) {
                    sendError(out, framedRequested, verified.getError());
                    metricsCollector.recordError("security_verify");
                    continue;
                }

                String[] parts = CommandParser.parse(verified.getCommand());
                if (parts.length == 0) {
                    continue;
                }

                String commandName = parts[0].toLowerCase();
                CommandRegistry.Entry entry = registry.getEntry(commandName);
                if (entry == null) {
                    sendError(out, framedRequested, "Unknown command: " + commandName + ". Type 'help' for available commands.");
                    metricsCollector.recordError("unknown_command");
                    continue;
                }

                String currentSessionId = sessionByClient.getOrDefault(clientId, sessionId);
                if (currentSessionId == null && !"auth".equals(commandName)) {
                    sendError(out, framedRequested,
                        "Authentication required. " +
                            "Use: auth <user> <password> (requires security.auth.password.enabled=true), " +
                            "or set security.anonymous.viewer=true for viewer access."
                    );
                    metricsCollector.recordError("unauthorized");
                    continue;
                }
                CommandContext context = new CommandContext(clientId, clientInfo, currentSessionId, framedRequested, streamRequested);
                CommandContextHolder.set(context);

                try {
                    metricsCollector.recordCommandStart(commandName);
                    if (streamRequested && config.isStreamingEnabled() && entry.getMeta().isStreamable()
                        && entry.getCommand() instanceof StreamCommand) {
                        String precheck = pipeline.validateAndAuthorize(commandName, parts, context);
                        if (precheck != null) {
                            sendError(out, framedRequested, precheck);
                            auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, false);
                            continue;
                        }
                        StreamCommand streamCommand = (StreamCommand) entry.getCommand();
                        StreamSink sink = new StreamSink() {
                            @Override
                            public void send(String data) {
                                try {
                                    sendData(out, framedRequested, data);
                                } catch (IOException e) {
                                    metricsCollector.recordError("protocol_write");
                                }
                            }

                            @Override
                            public void close(String summary) {
                                try {
                                    if (summary != null && !summary.isEmpty()) {
                                        sendData(out, framedRequested, summary);
                                    }
                                    if (framedRequested) {
                                        FrameCodec.writeFrame(out, Frame.end(), config.getFrameMaxPayload());
                                    }
                                } catch (IOException e) {
                                    metricsCollector.recordError("protocol_write");
                                }
                            }

                            @Override
                            public void error(String message) {
                                try {
                                    sendError(out, framedRequested, message);
                                } catch (IOException e) {
                                    metricsCollector.recordError("protocol_write");
                                }
                            }
                        };

                        streamCommand.executeStream(parts, sink);
                        // For legacy clients (non-framed), also emit END marker to support launcher improvements.
                        if (!framedRequested) {
                            try {
                                Utf8LineCodec.writeLine(out, "END", true);
                            } catch (Exception ignore) {
                                // ignore
                            }
                        }
                        auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, true);
                    } else {
                        CommandPipeline.Result result = pipeline.execute(entry, parts, context);
                        if (result.isSuccess()) {
                            sendData(out, framedRequested, result.getOutput());
                            if (framedRequested) {
                                FrameCodec.writeFrame(out, Frame.end(), config.getFrameMaxPayload());
                            }
                            auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, true);
                        } else {
                            sendError(out, framedRequested, result.getError());
                            auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, false);
                        }
                    }

                    long duration = System.currentTimeMillis() - commandStart;
                    metricsCollector.recordCommandComplete(commandName, duration);

                    if ("quit".equals(commandName)) {
                        break;
                    }
                } catch (Exception e) {
                    sendError(out, framedRequested, "Error executing command: " + e.getMessage());
                    metricsCollector.recordError("command_execution");
                    auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, false);
                } finally {
                    if (context.getSessionId() != null && !context.getSessionId().equals(currentSessionId)) {
                        if (currentSessionId != null) {
                            authenticationManager.logout(currentSessionId);
                        }
                        sessionByClient.put(clientId, context.getSessionId());
                    }
                    CommandContextHolder.clear();
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
                String activeSession = sessionByClient.remove(clientId);
                if (activeSession != null) {
                    authenticationManager.logout(activeSession);
                }
            } catch (IOException e) {
                // Ignore close errors
            }
        }
    }

    private void handleBinaryClient(DataInputStream in,
                                    DataOutputStream out,
                                    String clientId,
                                    String clientInfo,
                                    String baseSessionId) throws IOException {
        int maxPayloadBytes = config.getFrameMaxPayload();

        while (running.get()) {
            BinaryFrame frame;
            try {
                frame = BinaryFrameCodec.readFrame(in, maxPayloadBytes);
            } catch (IOException e) {
                metricsCollector.recordError("protocol_binary_decode");
                throw e;
            }

            if (frame == null) {
                break;
            }

            if (frame.getType() == BinaryFrame.Type.PING) {
                BinaryFrameCodec.writeFrame(out, BinaryFrame.pong(), maxPayloadBytes);
                continue;
            }

            if (frame.getType() != BinaryFrame.Type.REQUEST) {
                continue;
            }

            long commandStart = System.currentTimeMillis();
            boolean framedRequested = true;
            boolean streamRequested = frame.isStream();
            String raw = frame.getPayloadUtf8();

            String verifyKey = sessionByClient.get(clientId);
            if (verifyKey == null) {
                verifyKey = baseSessionId;
            }
            if (verifyKey == null) {
                verifyKey = clientId;
            }
            RequestSecurityManager.VerificationResult verified = requestSecurityManager.verifyAndExtract(verifyKey, raw);
            if (!verified.isOk()) {
                sendErrorBinary(out, verified.getError(), maxPayloadBytes);
                metricsCollector.recordError("security_verify");
                continue;
            }

            String[] parts = CommandParser.parse(verified.getCommand());
            if (parts.length == 0) {
                continue;
            }

            String commandName = parts[0].toLowerCase();
            CommandRegistry.Entry entry = registry.getEntry(commandName);
            if (entry == null) {
                sendErrorBinary(out, "Unknown command: " + commandName + ". Type 'help' for available commands.", maxPayloadBytes);
                metricsCollector.recordError("unknown_command");
                continue;
            }

            String currentSessionId = sessionByClient.getOrDefault(clientId, baseSessionId);
            if (currentSessionId == null && !"auth".equals(commandName)) {
                sendErrorBinary(out, "Authentication required. Use: auth <user> <password>", maxPayloadBytes);
                metricsCollector.recordError("unauthorized");
                continue;
            }

            CommandContext context = new CommandContext(clientId, clientInfo, currentSessionId, framedRequested, streamRequested);
            CommandContextHolder.set(context);

            try {
                metricsCollector.recordCommandStart(commandName);
                if (streamRequested && config.isStreamingEnabled() && entry.getMeta().isStreamable()
                    && entry.getCommand() instanceof StreamCommand) {
                    String precheck = pipeline.validateAndAuthorize(commandName, parts, context);
                    if (precheck != null) {
                        sendErrorBinary(out, precheck, maxPayloadBytes);
                        auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, false);
                        continue;
                    }

                    StreamCommand streamCommand = (StreamCommand) entry.getCommand();
                    StreamSink sink = new StreamSink() {
                        @Override
                        public void send(String data) {
                            try {
                                sendDataBinary(out, data, maxPayloadBytes);
                            } catch (IOException e) {
                                metricsCollector.recordError("protocol_binary_write");
                            }
                        }

                        @Override
                        public void close(String summary) {
                            try {
                                if (summary != null && !summary.isEmpty()) {
                                    sendDataBinary(out, summary, maxPayloadBytes);
                                }
                                BinaryFrameCodec.writeFrame(out, BinaryFrame.end(), maxPayloadBytes);
                            } catch (IOException e) {
                                metricsCollector.recordError("protocol_binary_write");
                            }
                        }

                        @Override
                        public void error(String message) {
                            try {
                                sendErrorBinary(out, message, maxPayloadBytes);
                            } catch (IOException e) {
                                metricsCollector.recordError("protocol_binary_write");
                            }
                        }
                    };

                    streamCommand.executeStream(parts, sink);
                    auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, true);
                } else {
                    CommandPipeline.Result result = pipeline.execute(entry, parts, context);
                    if (result.isSuccess()) {
                        sendDataBinary(out, result.getOutput(), maxPayloadBytes);
                        BinaryFrameCodec.writeFrame(out, BinaryFrame.end(), maxPayloadBytes);
                        auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, true);
                    } else {
                        sendErrorBinary(out, result.getError(), maxPayloadBytes);
                        auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, false);
                    }
                }

                long duration = System.currentTimeMillis() - commandStart;
                metricsCollector.recordCommandComplete(commandName, duration);

                if ("quit".equals(commandName)) {
                    break;
                }
            } catch (Exception e) {
                sendErrorBinary(out, "Error executing command: " + e.getMessage(), maxPayloadBytes);
                metricsCollector.recordError("command_execution");
                auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, false);
            } finally {
                if (context.getSessionId() != null && !context.getSessionId().equals(currentSessionId)) {
                    if (currentSessionId != null) {
                        authenticationManager.logout(currentSessionId);
                    }
                    sessionByClient.put(clientId, context.getSessionId());
                }
                CommandContextHolder.clear();
            }
        }
    }

    private void sendDataBinary(DataOutputStream out, String data, int maxPayloadBytes) throws IOException {
        if (data == null) {
            data = "";
        }
        BinaryFrameCodec.writeFrame(out, BinaryFrame.data(data), maxPayloadBytes);
    }

    private void sendErrorBinary(DataOutputStream out, String message, int maxPayloadBytes) throws IOException {
        if (message == null) {
            message = "Unknown error";
        }
        BinaryFrameCodec.writeFrame(out, BinaryFrame.err(message), maxPayloadBytes);
        BinaryFrameCodec.writeFrame(out, BinaryFrame.end(), maxPayloadBytes);
    }

    private String handleHello(String line, OutputStream out) throws IOException {
        metricsCollector.recordHandshake();
        Map<String, String> kv = parseHandshakeKv(line);
        Set<String> clientProtocols = parseProtocols(kv.get("protocols"));
        String requested = kv.get("protocol");
        if (requested != null && !requested.trim().isEmpty()) {
            clientProtocols.add(requested.trim().toLowerCase());
        }
        if (clientProtocols.isEmpty()) {
            clientProtocols.add("legacy");
        }

        String preferred = config.getProtocolMode();
        if (preferred == null) {
            preferred = "legacy";
        }
        preferred = preferred.toLowerCase();

        String selected;
        if ("binary".equals(preferred) && clientProtocols.contains("binary")) {
            selected = "binary";
        } else if ("framed".equals(preferred) && clientProtocols.contains("framed")) {
            selected = "framed";
        } else if ("binary".equals(preferred) && clientProtocols.contains("framed")) {
            selected = "framed";
        } else {
            selected = "legacy";
        }

        String connId = kv.get("connid");
        Utf8LineCodec.writeLine(out, buildConfigLine(selected, connId), true);
        return selected;
    }

    private String buildConfigLine(String protocol, String connId) {
        return "CONFIG v=1" +
            " protocol=" + protocol +
            " streaming=" + config.isStreamingEnabled() +
            " maxPayload=" + config.getFrameMaxPayload() +
            " port=" + config.getServerPort() +
            " bind=" + config.getServerBindAddress() +
            " securityMode=" + config.getSecurityMode() +
            " authorization=" + config.isAuthorizationEnabled() +
            (connId != null ? " connId=" + connId : "");
    }

    private static Map<String, String> parseHandshakeKv(String line) {
        Map<String, String> kv = new HashMap<>();
        if (line == null) {
            return kv;
        }
        String[] tokens = line.trim().split("\\s+");
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            int idx = token.indexOf('=');
            if (idx <= 0 || idx >= token.length() - 1) {
                continue;
            }
            String k = token.substring(0, idx).trim().toLowerCase();
            String v = token.substring(idx + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) {
                kv.put(k, v);
            }
        }
        return kv;
    }

    private static Set<String> parseProtocols(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null || csv.trim().isEmpty()) {
            return out;
        }
        String[] parts = csv.split(",");
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String v = p.trim().toLowerCase();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private static boolean isLoopbackBind(String bindAddress) {
        if (bindAddress == null) {
            return true;
        }
        String v = bindAddress.trim().toLowerCase();
        if (v.isEmpty()) {
            return true;
        }
        return "127.0.0.1".equals(v) || "localhost".equals(v) || "::1".equals(v);
    }

    private void sendData(OutputStream out, boolean framed, String data) throws IOException {
        if (data == null) {
            data = "";
        }
        if (framed) {
            FrameCodec.writeFrame(out, Frame.data(data), config.getFrameMaxPayload());
        } else {
            Utf8LineCodec.writeLine(out, data, true);
        }
    }

    private void sendError(OutputStream out, boolean framed, String message) throws IOException {
        if (message == null) {
            message = "Unknown error";
        }
        if (framed) {
            FrameCodec.writeFrame(out, Frame.err(message), config.getFrameMaxPayload());
            FrameCodec.writeFrame(out, Frame.end(), config.getFrameMaxPayload());
        } else {
            Utf8LineCodec.writeLine(out, message, true);
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

        // Release plugin classloader resources (important on Windows to avoid JAR locks)
        try {
            registry.shutdown();
        } catch (Exception ignore) {
            // ignore
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
        try {
            registry.shutdown();
        } catch (Exception ignore) {
            // ignore
        }
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
