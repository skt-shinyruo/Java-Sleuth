package com.javasleuth.command.server.protocol;

import com.javasleuth.command.protocol.KvLineCodec;
import com.javasleuth.command.CommandContext;
import com.javasleuth.command.CommandContextHolder;
import com.javasleuth.command.CommandParser;
import com.javasleuth.command.CommandErrorMapper;
import com.javasleuth.command.CommandPipeline;
import com.javasleuth.command.CommandRegistry;
import com.javasleuth.command.StreamCommand;
import com.javasleuth.command.StreamSink;
import com.javasleuth.command.session.ClientDisconnectedException;
import com.javasleuth.command.session.ClientSession;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.security.AuditLogger;
import com.javasleuth.security.AuthenticationManager;
import com.javasleuth.security.RequestSecurityManager;
import com.javasleuth.util.SleuthLogger;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CommandRequestExecutor {
    private final MetricsCollector metricsCollector;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final AuthenticationManager authenticationManager;
    private final RequestSecurityManager requestSecurityManager;
    private final CommandRegistry registry;
    private final CommandPipeline pipeline;
    private final ConcurrentHashMap<String, String> sessionByClient;

    public CommandRequestExecutor(
        MetricsCollector metricsCollector,
        ProductionConfig config,
        AuditLogger auditLogger,
        AuthenticationManager authenticationManager,
        RequestSecurityManager requestSecurityManager,
        CommandRegistry registry,
        CommandPipeline pipeline,
        ConcurrentHashMap<String, String> sessionByClient
    ) {
        this.metricsCollector = metricsCollector;
        this.config = config;
        this.auditLogger = auditLogger;
        this.authenticationManager = authenticationManager;
        this.requestSecurityManager = requestSecurityManager;
        this.registry = registry;
        this.pipeline = pipeline;
        this.sessionByClient = sessionByClient;
    }

    public boolean execute(
        String clientId,
        String clientInfo,
        String baseSessionId,
        String connId,
        ClientSession clientSession,
        boolean framedRequested,
        boolean streamRequested,
        String raw,
        CommandReplyChannel reply,
        String authRequiredMessage,
        String streamWriteMetricKey,
        String sendDisconnectedMessage,
        String closeDisconnectedMessage,
        String errorDisconnectedMessage
    ) throws IOException {
        long commandStart = System.currentTimeMillis();
        String verifyKey = sessionByClient.get(clientId);
        if (verifyKey == null) {
            verifyKey = baseSessionId;
        }
        if (verifyKey == null) {
            verifyKey = clientId;
        }

        if ("hmac".equalsIgnoreCase(config.getSecurityMode())) {
            Map<String, String> sigKv = KvLineCodec.parseAfterVerb(raw);
            String sid = sigKv.get("sid");
            if (sigKv.containsKey("v")) {
                reply.sendError("HMAC security: unsupported SIG field: v");
                metricsCollector.recordError("security_sig_version");
                return false;
            }
            if (connId == null || connId.trim().isEmpty()) {
                reply.sendError("HMAC security: connId is required (handshake missing)");
                metricsCollector.recordError("security_sig_connid");
                return false;
            }
            if (sid == null || !connId.equals(sid)) {
                reply.sendError("HMAC security: SIG sid must match negotiated connId");
                metricsCollector.recordError("security_sig_sid");
                return false;
            }
        }

        RequestSecurityManager.VerificationResult verified = requestSecurityManager.verifyAndExtract(verifyKey, raw);
        if (!verified.isOk()) {
            reply.sendError(verified.getError());
            metricsCollector.recordError("security_verify");
            return false;
        }

        String[] parts = CommandParser.parse(verified.getCommand());
        if (parts.length == 0) {
            return false;
        }

        String commandName = parts[0].toLowerCase(Locale.ROOT);
        CommandRegistry.Entry entry = registry.getEntry(commandName);
        if (entry == null) {
            reply.sendError("Unknown command: " + commandName + ". Type 'help' for available commands.");
            metricsCollector.recordError("unknown_command");
            return false;
        }

        String currentSessionId = sessionByClient.getOrDefault(clientId, baseSessionId);
        if (currentSessionId == null && !"auth".equals(commandName)) {
            reply.sendError(authRequiredMessage);
            metricsCollector.recordError("unauthorized");
            return false;
        }

        CommandContext context = new CommandContext(
            clientId,
            clientInfo,
            currentSessionId,
            connId,
            commandName,
            framedRequested,
            streamRequested,
            clientSession
        );
        CommandContextHolder.set(context);

        try {
            metricsCollector.recordCommandStart(commandName);
            CommandPipeline.PrecheckResult precheck = pipeline.precheck(entry, commandName, parts, context);
            if (!precheck.isOk()) {
                reply.sendError(precheck.getError());
                String[] auditArgs = precheck.getArgs() != null ? precheck.getArgs() : parts;
                auditLogger.logCommandExecution(clientId, clientInfo, commandName, auditArgs, false);
                return false;
            }

            String[] execArgs = precheck.getArgs();
            if (streamRequested && config.isStreamingEnabled() && entry.getMeta().isStreamable()
                && entry.getCommand() instanceof StreamCommand) {
                boolean streamSuccess = false;

                StreamSink sink = createStreamSink(
                    reply,
                    clientSession,
                    streamWriteMetricKey,
                    sendDisconnectedMessage,
                    closeDisconnectedMessage,
                    errorDisconnectedMessage
                );

                CommandPipeline.StreamResult streamResult = pipeline.executeStreamPrechecked(entry, execArgs, context, sink);
                streamSuccess = streamResult != null && streamResult.isSuccess();

                auditLogger.logCommandExecution(clientId, clientInfo, commandName, execArgs, streamSuccess);
                long duration = System.currentTimeMillis() - commandStart;
                if (streamSuccess) {
                    metricsCollector.recordCommandComplete(commandName, duration);
                } else {
                    metricsCollector.recordCommandError(commandName, duration);
                }
            } else {
                CommandPipeline.Result result = pipeline.executePrechecked(entry, execArgs, context);
                if (result.isSuccess()) {
                    reply.sendData(result.getOutput());
                    reply.end();
                    auditLogger.logCommandExecution(clientId, clientInfo, commandName, execArgs, true);
                    long duration = System.currentTimeMillis() - commandStart;
                    metricsCollector.recordCommandComplete(commandName, duration);
                } else {
                    reply.sendError(result.getError());
                    auditLogger.logCommandExecution(clientId, clientInfo, commandName, execArgs, false);
                    long duration = System.currentTimeMillis() - commandStart;
                    metricsCollector.recordCommandError(commandName, duration);
                }
            }

            return "quit".equals(commandName);
        } catch (ClientDisconnectedException disconnected) {
            throw disconnected;
        } catch (Exception e) {
            String errorId = CommandErrorMapper.newErrorId();
            SleuthLogger.error("Command request execution failed (errorId=" + errorId + ")", e);
            reply.sendError(CommandErrorMapper.toUserMessage(e, errorId, context));
            metricsCollector.recordError("command_execution");
            auditLogger.logCommandExecution(clientId, clientInfo, commandName, parts, false);
            return false;
        } finally {
            try {
                if (context.getSessionId() != null && !context.getSessionId().equals(currentSessionId)) {
                    if (currentSessionId != null) {
                        authenticationManager.logout(currentSessionId);
                    }
                    sessionByClient.put(clientId, context.getSessionId());
                    clientSession.setSessionId(context.getSessionId());
                }
            } catch (Exception ignore) {
                SleuthLogger.debug("Failed to sync session update after command: " + ignore.getMessage(), ignore);
            }
            CommandContextHolder.clear();
        }
    }

    private StreamSink createStreamSink(
        CommandReplyChannel reply,
        ClientSession clientSession,
        String writeMetricKey,
        String sendDisconnectedMessage,
        String closeDisconnectedMessage,
        String errorDisconnectedMessage
    ) {
        return new StreamSink() {
            @Override
            public void send(String data) {
                try {
                    reply.sendData(data);
                } catch (IOException e) {
                    metricsCollector.recordError(writeMetricKey);
                    try {
                        clientSession.close("client_write_failed");
                    } catch (Exception ignore) {
                        SleuthLogger.debug("Failed to close client session after write failure: " + ignore.getMessage(), ignore);
                    }
                    throw new ClientDisconnectedException(sendDisconnectedMessage);
                }
            }

            @Override
            public void close(String summary) {
                try {
                    if (summary != null && !summary.isEmpty()) {
                        reply.sendData(summary);
                    }
                    reply.end();
                } catch (IOException e) {
                    metricsCollector.recordError(writeMetricKey);
                    try {
                        clientSession.close("client_write_failed");
                    } catch (Exception ignore) {
                        SleuthLogger.debug("Failed to close client session after close failure: " + ignore.getMessage(), ignore);
                    }
                    throw new ClientDisconnectedException(closeDisconnectedMessage);
                }
            }

            @Override
            public void error(String message) {
                try {
                    reply.sendError(message);
                } catch (IOException e) {
                    metricsCollector.recordError(writeMetricKey);
                    try {
                        clientSession.close("client_write_failed");
                    } catch (Exception ignore) {
                        SleuthLogger.debug("Failed to close client session after error write failure: " + ignore.getMessage(), ignore);
                    }
                    throw new ClientDisconnectedException(errorDisconnectedMessage);
                }
            }
        };
    }
}
