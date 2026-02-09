package com.javasleuth.command.server;

import com.javasleuth.command.CommandPipeline;
import com.javasleuth.command.CommandRegistry;
import com.javasleuth.command.protocol.Utf8LineCodec;
import com.javasleuth.command.server.protocol.BinaryClientProtocolHandler;
import com.javasleuth.command.server.protocol.CommandRequestExecutor;
import com.javasleuth.command.server.protocol.FramedClientCommandHandler;
import com.javasleuth.command.server.protocol.HandshakeNegotiator;
import com.javasleuth.command.session.ClientDisconnectedException;
import com.javasleuth.command.session.ClientSession;
import com.javasleuth.command.session.ClientSessionRegistry;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.security.AuditLogger;
import com.javasleuth.security.AuthenticationManager;
import com.javasleuth.security.RequestSecurityManager;
import com.javasleuth.util.SleuthLogContext;
import com.javasleuth.util.SleuthLogger;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个客户端连接的处理逻辑（framed/binary）。
 *
 * <p>将 CommandProcessor 中“协议 + 会话 + 调度 + 回写”部分拆出，降低巨型类维护成本。</p>
 */
public final class CommandClientHandler {
    private final AtomicBoolean running;
    private final AtomicLong commandCounter;
    private final MetricsCollector metricsCollector;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final AuthenticationManager authenticationManager;
    private final RequestSecurityManager requestSecurityManager;
    private final CommandRegistry registry;
    private final CommandPipeline pipeline;
    private final ConcurrentHashMap<String, String> sessionByClient;
    private final ClientSessionRegistry clientSessionRegistry;

    private final HandshakeNegotiator handshakeNegotiator;
    private final CommandRequestExecutor requestExecutor;
    private final BinaryClientProtocolHandler binaryProtocolHandler;

    public CommandClientHandler(
        AtomicBoolean running,
        AtomicLong commandCounter,
        MetricsCollector metricsCollector,
        ProductionConfig config,
        AuditLogger auditLogger,
        AuthenticationManager authenticationManager,
        RequestSecurityManager requestSecurityManager,
        CommandRegistry registry,
        CommandPipeline pipeline,
        ConcurrentHashMap<String, String> sessionByClient,
        ClientSessionRegistry clientSessionRegistry
    ) {
        this.running = running;
        this.commandCounter = commandCounter;
        this.metricsCollector = metricsCollector;
        this.config = config;
        this.auditLogger = auditLogger;
        this.authenticationManager = authenticationManager;
        this.requestSecurityManager = requestSecurityManager;
        this.registry = registry;
        this.pipeline = pipeline;
        this.sessionByClient = sessionByClient;
        this.clientSessionRegistry = clientSessionRegistry;

        this.handshakeNegotiator = new HandshakeNegotiator(config, metricsCollector);
        this.requestExecutor = new CommandRequestExecutor(
            metricsCollector,
            config,
            auditLogger,
            authenticationManager,
            requestSecurityManager,
            registry,
            pipeline,
            sessionByClient
        );
        this.binaryProtocolHandler = new BinaryClientProtocolHandler(running, metricsCollector, config, requestExecutor);
    }

    public void handle(Socket clientSocket) {
        long sessionStart = System.currentTimeMillis();
        String clientId = "client-" + commandCounter.incrementAndGet();
        String clientInfo = clientSocket.getRemoteSocketAddress().toString();
        String sessionId = null;
        String connId = null;
        final ClientSession clientSession;

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
        clientSession = clientSessionRegistry.open(clientId, clientInfo, sessionId);
        SleuthLogContext.setConnection(clientId, sessionId, connId);

        boolean pendingBinaryUpgrade = false;
        boolean handshakeCompleted = false;

        int maxLineBytes = config.getInt(
            "protocol.text.max.line.bytes",
            Math.max(8192, config.getFrameMaxPayload() * 2)
        );

        try (BufferedInputStream in = new BufferedInputStream(clientSocket.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(clientSocket.getOutputStream())) {

            FramedClientCommandHandler framedHandler =
                new FramedClientCommandHandler(requestExecutor, out, config.getFrameMaxPayload());

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

                if (line.regionMatches(true, 0, "HELLO", 0, "HELLO".length())) {
                    HandshakeNegotiator.NegotiationResult negotiated = handshakeNegotiator.handleHello(line, out);
                    connId = negotiated.getConnId();
                    String selected = negotiated.getProtocol();
                    pendingBinaryUpgrade = "binary".equalsIgnoreCase(selected);
                    handshakeCompleted = true;
                    SleuthLogContext.setConnection(clientId, sessionId, connId);
                    continue;
                }

                if (!handshakeCompleted) {
                    Utf8LineCodec.writeLine(out, "Handshake required. Send: HELLO", true);
                    metricsCollector.recordError("handshake_required");
                    continue;
                }

                if (pendingBinaryUpgrade) {
                    if ("UPGRADE BINARY".equalsIgnoreCase(line)) {
                        Utf8LineCodec.writeLine(out, "OK", true);
                        metricsCollector.recordBinaryUpgrade();
                        binaryProtocolHandler.handle(
                            new DataInputStream(in),
                            new DataOutputStream(out),
                            clientId,
                            clientInfo,
                            sessionId,
                            connId,
                            clientSession
                        );
                        break;
                    } else {
                        Utf8LineCodec.writeLine(out, "Handshake pending. Send: UPGRADE BINARY", true);
                        continue;
                    }
                }

                final boolean streamRequested;
                final String raw;
                if (line.startsWith("CMD ")) {
                    streamRequested = false;
                    raw = line.substring(4);
                } else if (line.startsWith("STREAM ")) {
                    streamRequested = true;
                    raw = line.substring(7);
                } else {
                    Utf8LineCodec.writeLine(
                        out,
                        "Protocol error: expected CMD <signed_command> or STREAM <signed_command>.",
                        true
                    );
                    metricsCollector.recordError("protocol_invalid_prefix");
                    break;
                }

                try {
                    boolean shouldClose =
                        framedHandler.handle(raw, streamRequested, clientId, clientInfo, sessionId, connId, clientSession);
                    if (shouldClose) {
                        break;
                    }
                } catch (ClientDisconnectedException disconnected) {
                    break;
                }
            }
        } catch (IOException e) {
            SleuthLogger.warn("Error handling client " + clientId + ": " + e.getMessage(), e);
            metricsCollector.recordError("client_io_error");
        } finally {
            SleuthLogContext.clear();
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
                clientSessionRegistry.close(clientId, "disconnect");
            } catch (IOException e) {
                // Ignore close errors
            }
        }
    }
}
