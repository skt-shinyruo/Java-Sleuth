package com.javasleuth.core.command.server;

/**
 * 单个客户端连接的处理逻辑（握手、协议协商、命令执行与断连清理）。
 *
 * <p>该类通过注入依赖运行，避免在 CommandProcessor 中继续堆叠协议与会话细节。</p>
 */
import com.javasleuth.core.command.CommandPipeline;
import com.javasleuth.core.command.CommandRegistry;
import com.javasleuth.foundation.command.protocol.Utf8LineCodec;
import com.javasleuth.core.command.server.protocol.BinaryClientProtocolHandler;
import com.javasleuth.core.command.server.protocol.CommandRequestExecutor;
import com.javasleuth.core.command.server.protocol.FramedClientCommandHandler;
import com.javasleuth.core.command.server.protocol.HandshakeNegotiator;
import com.javasleuth.core.command.session.ClientDisconnectedException;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.core.command.session.ClientSessionIndex;
import com.javasleuth.core.command.session.ClientSessionRegistry;
import com.javasleuth.foundation.config.ConfigSnapshot;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.ProtocolConfig;
import com.javasleuth.foundation.config.model.SecurityConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.RequestSecurityManager;
import com.javasleuth.foundation.util.SleuthLogContext;
import com.javasleuth.foundation.util.SleuthLogger;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
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
    private final ClientSessionIndex sessionIndex;
    private final ClientSessionRegistry clientSessionRegistry;

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
        ClientSessionIndex sessionIndex,
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
        this.sessionIndex = sessionIndex;
        this.clientSessionRegistry = clientSessionRegistry;

        this.requestExecutor = new CommandRequestExecutor(
            metricsCollector,
            config,
            auditLogger,
            authenticationManager,
            requestSecurityManager,
            registry,
            pipeline,
            sessionIndex
        );
        this.binaryProtocolHandler = new BinaryClientProtocolHandler(running, metricsCollector, requestExecutor);
    }

    public void handle(Socket clientSocket) {
        long sessionStart = System.currentTimeMillis();
        String clientId = "client-" + commandCounter.incrementAndGet();
        String clientInfo = clientSocket.getRemoteSocketAddress().toString();
        String sessionId = null;
        String connId = null;
        ClientSession clientSession = null;

        boolean pendingBinaryUpgrade = false;
        boolean handshakeCompleted = false;

        // 会话边界解析配置：避免在握手/循环中散落字符串 key + 默认值。
        boolean abort = false;
        SleuthConfig sessionConfig;
        ProtocolConfig protocolConfig;
        SecurityConfig securityConfig;
        int maxLineBytes;
        int maxPayloadBytes;

        try {
            ConfigSnapshot snapshot = config.snapshot();
            sessionConfig = SleuthConfigParser.parse(snapshot);
            protocolConfig = sessionConfig.protocol();
            securityConfig = sessionConfig.security();
            maxLineBytes = protocolConfig.getTextMaxLineBytes();
            maxPayloadBytes = protocolConfig.getFrameMaxPayloadBytes();
        } catch (Exception e) {
            SleuthLogger.warn("Invalid configuration for client " + clientId + ": " + e.getMessage(), e);
            metricsCollector.recordError("config_invalid");
            // Best-effort: try to notify client, but still close the socket.
            try (BufferedOutputStream out = new BufferedOutputStream(clientSocket.getOutputStream())) {
                Utf8LineCodec.writeLine(out, "CONFIG ERROR: " + e.getMessage(), true);
            } catch (Exception ignore) {
                // ignore
            }
            abort = true;
            sessionConfig = null;
            protocolConfig = null;
            securityConfig = null;
            maxLineBytes = 8192;
            maxPayloadBytes = 4096;
        }

        try {
            if (abort) {
                return;
            }
            if (sessionConfig != null) {
                AuthenticationManager.UserRole initialRole = AuthenticationManager.UserRole.VIEWER;
                if (securityConfig != null && securityConfig.isHmacEnabled()) {
                    initialRole = AuthenticationManager.UserRole.fromName(
                        securityConfig.getHmacSessionRole(),
                        AuthenticationManager.UserRole.OPERATOR
                    );
                }

                AuthenticationManager.AuthenticationResult sessionResult =
                    authenticationManager.createSession(initialRole, clientInfo);
                if (sessionResult.isSuccess()) {
                    sessionId = sessionResult.getSessionId();
                    sessionIndex.put(clientId, sessionId);
                }
            }

            clientSession = clientSessionRegistry.open(clientId, clientInfo, sessionId);
            SleuthLogContext.setConnection(clientId, sessionId, connId);

            try (BufferedInputStream in = new BufferedInputStream(clientSocket.getInputStream());
                 BufferedOutputStream out = new BufferedOutputStream(clientSocket.getOutputStream())) {

                FramedClientCommandHandler framedHandler =
                    new FramedClientCommandHandler(requestExecutor, out, maxPayloadBytes);

                HandshakeNegotiator handshakeNegotiator =
                    new HandshakeNegotiator(sessionConfig, metricsCollector);

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
                                maxPayloadBytes,
                                clientId,
                                clientInfo,
                                sessionId,
                                connId,
                                clientSession,
                                protocolConfig,
                                securityConfig
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
                            framedHandler.handle(
                                raw,
                                streamRequested,
                                clientId,
                                clientInfo,
                                sessionId,
                                connId,
                                clientSession,
                                protocolConfig,
                                securityConfig
                            );
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
            }
        } finally {
            SleuthLogContext.clear();
            try {
                clientSocket.close();
                long sessionDuration = System.currentTimeMillis() - sessionStart;
                metricsCollector.recordSessionEnd(clientId, sessionDuration);
                auditLogger.logConnectionEvent(clientId, clientInfo, "DISCONNECT");
                metricsCollector.recordClientDisconnection();
                String activeSession = sessionIndex.remove(clientId);
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
