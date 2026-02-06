package com.javasleuth.command.server.protocol;

import com.javasleuth.command.session.ClientSession;
import java.io.IOException;
import java.io.OutputStream;

public final class TextClientCommandHandler {
    private static final String AUTH_REQUIRED_MESSAGE =
        "Authentication required. " +
            "Use: auth <user> <password> (requires security.auth.password.enabled=true), " +
            "or set security.anonymous.viewer=true for viewer access.";

    private final CommandRequestExecutor executor;
    private final CommandReplyChannel reply;

    public TextClientCommandHandler(CommandRequestExecutor executor, OutputStream out) {
        this.executor = executor;
        this.reply = new TextReplyChannel(out);
    }

    public boolean handle(
        String raw,
        boolean streamRequested,
        String clientId,
        String clientInfo,
        String baseSessionId,
        String connId,
        ClientSession clientSession
    ) throws IOException {
        return executor.execute(
            clientId,
            clientInfo,
            baseSessionId,
            connId,
            clientSession,
            false,
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

