package com.javasleuth.core.command.server.protocol;

import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.foundation.config.model.ProtocolConfig;
import com.javasleuth.foundation.config.model.SecurityConfig;
import java.io.IOException;
import java.io.OutputStream;

public final class FramedClientCommandHandler {
    private static final String AUTH_REQUIRED_MESSAGE =
        "Authentication required. " +
            "Use: auth <user> <password> (requires security.auth.password.enabled=true), " +
            "or set security.anonymous.viewer=true for viewer access.";

    private final CommandRequestExecutor executor;
    private final CommandReplyChannel reply;

    public FramedClientCommandHandler(CommandRequestExecutor executor, OutputStream out, int maxPayloadBytes) {
        this.executor = executor;
        this.reply = new FramedReplyChannel(out, maxPayloadBytes);
    }

    public boolean handle(
        String raw,
        boolean streamRequested,
        String clientId,
        String clientInfo,
        String baseSessionId,
        String connId,
        ClientSession clientSession,
        ProtocolConfig protocolConfig,
        SecurityConfig securityConfig
    ) throws IOException {
        return executor.execute(
            clientId,
            clientInfo,
            baseSessionId,
            connId,
            clientSession,
            protocolConfig,
            securityConfig,
            true,
            streamRequested,
            raw,
            reply,
            AUTH_REQUIRED_MESSAGE,
            "protocol_write",
            "Client connection closed during send",
            "Client connection closed during close",
            "Client connection closed during error"
        );
    }
}
