package com.javasleuth.command;

import com.javasleuth.command.session.ClientSession;

public class CommandContext {
    private final String clientId;
    private final String clientInfo;
    private String sessionId;
    private final boolean framed;
    private final boolean streaming;
    private final ClientSession clientSession;

    public CommandContext(String clientId, String clientInfo, String sessionId, boolean framed, boolean streaming) {
        this(clientId, clientInfo, sessionId, framed, streaming, null);
    }

    public CommandContext(String clientId,
                          String clientInfo,
                          String sessionId,
                          boolean framed,
                          boolean streaming,
                          ClientSession clientSession) {
        this.clientId = clientId;
        this.clientInfo = clientInfo;
        this.sessionId = sessionId;
        this.framed = framed;
        this.streaming = streaming;
        this.clientSession = clientSession;
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
