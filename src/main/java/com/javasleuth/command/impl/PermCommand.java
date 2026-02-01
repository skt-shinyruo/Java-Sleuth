package com.javasleuth.command.impl;

import com.javasleuth.command.Command;

/**
 * Simplified permissions helper.
 *
 * <p>Java-Sleuth enforces permissions via AuthorizationManager + session roles.
 * This command is intentionally informational (no security decision logic here).
 */
public class PermCommand implements Command {
    @Override
    public String execute(String[] args) {
        return "Roles: viewer < operator < admin\n" +
            "Use `session` to check current role.\n" +
            "Use `auth <username> <password>` to upgrade role (requires security.auth.password.enabled=true and a configured password).\n" +
            "Note: dangerous commands (redefine/retransform/mc/heapdump/stop/reset/vmoption set) require admin.";
    }

    @Override
    public String getDescription() {
        return "Show permission model (roles) and how to authenticate";
    }
}
