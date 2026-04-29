package com.javasleuth.core.command;

import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.core.command.spec.ParsedCommand;

public class CommandContext {
    private final String clientId;
    private final String clientInfo;
    private String sessionId;
    private final String connId;
    private final String commandName;
    private final boolean streaming;
    private final ClientSession clientSession;
    private final CancellationToken cancellationToken;
    private final ParsedCommand parsedCommand;

    public CommandContext(String clientId, String clientInfo, String sessionId, boolean streaming) {
        this(clientId, clientInfo, sessionId, null, null, streaming, null, CancellationToken.NONE, null);
    }

    public CommandContext(String clientId,
                          String clientInfo,
                          String sessionId,
                          String connId,
                          String commandName,
                          boolean streaming,
                          ClientSession clientSession) {
        this(clientId, clientInfo, sessionId, connId, commandName, streaming, clientSession, CancellationToken.NONE, null);
    }

    public CommandContext(String clientId,
                          String clientInfo,
                          String sessionId,
                          String connId,
                          String commandName,
                          boolean streaming,
                          ClientSession clientSession,
                          CancellationToken cancellationToken) {
        this(clientId, clientInfo, sessionId, connId, commandName, streaming, clientSession, cancellationToken, null);
    }

    private CommandContext(String clientId,
                           String clientInfo,
                           String sessionId,
                           String connId,
                           String commandName,
                           boolean streaming,
                           ClientSession clientSession,
                           CancellationToken cancellationToken,
                           ParsedCommand parsedCommand) {
        this.clientId = clientId;
        this.clientInfo = clientInfo;
        this.sessionId = sessionId;
        this.connId = connId;
        this.commandName = commandName;
        this.streaming = streaming;
        this.clientSession = clientSession;
        this.cancellationToken = cancellationToken != null ? cancellationToken : CancellationToken.NONE;
        this.parsedCommand = parsedCommand;
    }

    public CommandContext(String clientId,
                          String clientInfo,
                          String sessionId,
                          boolean streaming,
                          ClientSession clientSession) {
        this(clientId, clientInfo, sessionId, null, null, streaming, clientSession);
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientInfo() {
        return clientInfo;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getConnId() {
        return connId;
    }

    public String getCommandName() {
        return commandName;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public ClientSession getClientSession() {
        return clientSession;
    }

    public CancellationToken getCancellationToken() {
        return cancellationToken != null ? cancellationToken : CancellationToken.NONE;
    }

    public ParsedCommand getParsedCommand() {
        return parsedCommand;
    }

    public CommandContext withCancellationToken(CancellationToken token) {
        return new CommandContext(clientId, clientInfo, sessionId, connId, commandName, streaming, clientSession, token, parsedCommand);
    }

    public CommandContext withParsedCommand(ParsedCommand parsed) {
        return new CommandContext(clientId, clientInfo, sessionId, connId, commandName, streaming, clientSession, cancellationToken, parsed);
    }
}
