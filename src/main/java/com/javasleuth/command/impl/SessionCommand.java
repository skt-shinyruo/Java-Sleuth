package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.command.CommandContext;
import com.javasleuth.command.CommandContextHolder;
import com.javasleuth.security.AuthenticationManager;

public class SessionCommand implements Command {
    @Override
    public String execute(String[] args) {
        CommandContext ctx = CommandContextHolder.get();
        if (ctx == null) {
            return "No active command context.";
        }

        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            return "Session: <null> (not authenticated).";
        }

        AuthenticationManager auth = AuthenticationManager.getInstance();
        AuthenticationManager.SessionValidationResult r = auth.validateSession(sessionId);
        if (!r.isValid()) {
            return "Session invalid: " + r.getMessage();
        }

        return "ClientId=" + ctx.getClientId() +
            ", ClientInfo=" + ctx.getClientInfo() +
            ", SessionId=" + sessionId +
            ", Role=" + r.getRole().getName();
    }

    @Override
    public String getDescription() {
        return "Show current session info (id/role/client)";
    }
}

