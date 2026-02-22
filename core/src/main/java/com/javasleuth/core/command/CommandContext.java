package com.javasleuth.core.command;

import com.javasleuth.core.command.session.ClientSession;

public class CommandContext {
    private final String clientId;
    private final String clientInfo;
    private String sessionId;
    private final String connId;
    private final String commandName;
    private final boolean framed;
    private final boolean streaming;
    private final ClientSession clientSession;

    public CommandContext(String clientId, String clientInfo, String sessionId, boolean framed, boolean streaming) {
        this(clientId, clientInfo, sessionId, null, null, framed, streaming, null);
    }

    public CommandContext(String clientId,
                          String clientInfo,
                          String sessionId,
                          String connId,
                          String commandName,
                          boolean framed,
                          boolean streaming,
                          ClientSession clientSession) {
        this.clientId = clientId;
        this.clientInfo = clientInfo;
        this.sessionId = sessionId;
        this.connId = connId;
        this.commandName = commandName;
        this.framed = framed;
        this.streaming = streaming;
        this.clientSession = clientSession;
    }

    public CommandContext(String clientId,
                          String clientInfo,
                          String sessionId,
                          boolean framed,
                          boolean streaming,
                          ClientSession clientSession) {
        this(clientId, clientInfo, sessionId, null, null, framed, streaming, clientSession);
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

    public boolean isFramed() {
        return framed;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public ClientSession getClientSession() {
        return clientSession;
    }
}
