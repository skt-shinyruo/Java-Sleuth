package com.javasleuth.core.command.server.protocol;

/**
 * 单次命令请求的执行入口（安全校验、解析、调度与回写）。
 *
 * <p>该类不直接依赖 CommandProcessor 的内部状态，通过注入的会话索引与 pipeline 执行。</p>
 */
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.CommandParser;
import com.javasleuth.core.command.CommandErrorMapper;
import com.javasleuth.core.command.CommandPipeline;
import com.javasleuth.core.command.CommandRegistry;
import com.javasleuth.core.command.StreamCommand;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.pipeline.StreamCompletion;
import com.javasleuth.core.command.pipeline.StreamExecutionHandle;
import com.javasleuth.core.command.session.ClientDisconnectedException;
import com.javasleuth.core.command.session.ClientSessionIndex;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.ProtocolConfig;
import com.javasleuth.foundation.config.model.SecurityConfig;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.util.SleuthLogger;
import com.javasleuth.foundation.util.SleuthThreadFactory;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ThreadFactory;

public final class CommandRequestExecutor {
    private final MetricsCollector metricsCollector;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final AuthenticationManager authenticationManager;
    private final CommandRegistry registry;
    private final CommandPipeline pipeline;
    private final ClientSessionIndex sessionIndex;
    private final ThreadFactory streamCompletionObserverFactory =
        SleuthThreadFactory.daemon("sleuth-stream-completion-observer");

    public CommandRequestExecutor(
        MetricsCollector metricsCollector,
        ProductionConfig config,
        AuditLogger auditLogger,
        AuthenticationManager authenticationManager,
        CommandRegistry registry,
        CommandPipeline pipeline,
        ClientSessionIndex sessionIndex
    ) {
        this.metricsCollector = metricsCollector;
        this.config = config;
        this.auditLogger = auditLogger;
        this.authenticationManager = authenticationManager;
        this.registry = registry;
        this.pipeline = pipeline;
        this.sessionIndex = sessionIndex;
    }

    public boolean execute(
        String clientId,
        String clientInfo,
        String baseSessionId,
        String connId,
        ClientSession clientSession,
        ProtocolConfig protocolConfig,
        SecurityConfig securityConfig,
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
        String commandLine = raw != null ? raw.trim() : "";
        if (commandLine.startsWith("SIG ")) {
            reply.sendError("Unsupported request envelope: SIG (HMAC mode removed)");
            metricsCollector.recordError("security_sig_removed");
            return false;
        }

        String[] parts = CommandParser.parse(commandLine);
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

        String currentSessionId = sessionIndex.getOrDefault(clientId, baseSessionId);
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
            boolean streamingEnabled = protocolConfig != null && protocolConfig.isStreamingEnabled();
            if (streamRequested && streamingEnabled && entry.getMeta().isStreamable()
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
                StreamExecutionHandle handle = streamResult != null ? streamResult.getHandle() : null;
                if (streamSuccess && handle != null) {
                    observeStreamCompletion(
                        handle,
                        clientId,
                        clientInfo,
                        commandName,
                        execArgs,
                        commandStart
                    );
                    return "quit".equals(commandName);
                }

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
                    sessionIndex.put(clientId, context.getSessionId());
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

    private void observeStreamCompletion(
        StreamExecutionHandle handle,
        String clientId,
        String clientInfo,
        String commandName,
        String[] execArgs,
        long commandStart
    ) {
        Thread observer = streamCompletionObserverFactory.newThread(() -> {
            boolean streamSuccess = false;
            try {
                StreamCompletion completion = handle.awaitCompletion();
                streamSuccess = completion != null && completion.isSuccess();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                handle.cancel("completion_observer_interrupted");
            } catch (Throwable t) {
                SleuthLogger.warn("Stream completion observer failed for command " + commandName + ": " + t.getMessage(), t);
            } finally {
                auditLogger.logCommandExecution(clientId, clientInfo, commandName, execArgs, streamSuccess);
                long duration = System.currentTimeMillis() - commandStart;
                if (streamSuccess) {
                    metricsCollector.recordCommandComplete(commandName, duration);
                } else {
                    metricsCollector.recordCommandError(commandName, duration);
                }
            }
        });
        observer.start();
    }
}
