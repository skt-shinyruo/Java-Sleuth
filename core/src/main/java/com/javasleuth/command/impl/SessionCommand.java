package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.command.CommandContext;
import com.javasleuth.command.CommandContextHolder;
import com.javasleuth.security.AuthenticationManager;

public class SessionCommand implements Command {
    private final AuthenticationManager authManager;

    public SessionCommand(AuthenticationManager authManager) {
        if (authManager == null) {
            throw new IllegalArgumentException("authManager");
        }
        this.authManager = authManager;
    }

    @Override
    public String execute(String[] args) {
        CommandContext ctx = CommandContextHolder.get();
        if (ctx == null) {
            return "No active command context.";
        }

        boolean showToken = false;
        if (args != null) {
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if ("--show-token".equalsIgnoreCase(a) || "--full".equalsIgnoreCase(a)) {
                    showToken = true;
                }
                if ("-h".equalsIgnoreCase(a) || "--help".equalsIgnoreCase(a)) {
                    return getHelp();
                }
            }
        }

        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            return "Session: <null> (not authenticated).";
        }

        AuthenticationManager.SessionValidationResult r = authManager.validateSession(sessionId);
        if (!r.isValid()) {
            return "Session invalid: " + r.getMessage();
        }

        return "ClientId=" + ctx.getClientId() +
            ", ClientInfo=" + ctx.getClientInfo() +
            ", SessionId=" + (showToken ? sessionId : maskToken(sessionId)) +
            ", Role=" + r.getRole().getName();
    }

    private String getHelp() {
        return "Session command usage:\n" +
            "  session [--show-token]\n\n" +
            "Options:\n" +
            "  --show-token, --full    Show full SessionId (sensitive)\n";
    }

    private String maskToken(String token) {
        if (token == null) {
            return "<null>";
        }
        String t = token.trim();
        if (t.isEmpty()) {
            return "<empty>";
        }
        if (t.length() <= 8) {
            return "****";
        }
        return t.substring(0, 4) + "****" + t.substring(t.length() - 4);
    }

    @Override
    public String getDescription() {
        return "Show current session info (id/role/client), token masked by default";
    }
}
