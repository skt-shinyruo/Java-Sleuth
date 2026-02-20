package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.command.CommandContext;
import com.javasleuth.command.CommandContextHolder;
import com.javasleuth.security.AuthenticationManager;

public class AuthCommand implements Command {
    private final AuthenticationManager authManager;

    public AuthCommand(AuthenticationManager authManager) {
        if (authManager == null) {
            throw new IllegalArgumentException("authManager");
        }
        this.authManager = authManager;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length < 3) {
            return "Usage: auth <username> <password>";
        }

        CommandContext context = CommandContextHolder.get();
        String clientInfo = context != null ? context.getClientInfo() : "unknown";

        AuthenticationManager.AuthenticationResult result =
            authManager.authenticate(args[1], args[2], clientInfo);

        if (!result.isSuccess()) {
            return "Authentication failed: " + result.getMessage();
        }

        if (context != null) {
            context.setSessionId(result.getSessionId());
        }

        return "Authenticated as " + result.getRole().getName() + ".";
    }

    @Override
    public String getDescription() {
        return "Authenticate and upgrade session role";
    }
}
