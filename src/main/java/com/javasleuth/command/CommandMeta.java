package com.javasleuth.command;

import com.javasleuth.security.AuthenticationManager.UserRole;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class CommandMeta {
    private final UserRole requiredRole;
    private final boolean cacheable;
    private final boolean streamable;
    private final boolean requiresAudit;
    private final int maxExecutionsPerMinute;
    private final boolean dangerous;
    private final Map<String, UserRole> subcommandRoles;

    public CommandMeta(UserRole requiredRole, boolean cacheable, boolean streamable) {
        this(requiredRole, cacheable, streamable,
            defaultRequiresAudit(requiredRole),
            defaultRateLimit(requiredRole),
            false,
            Collections.emptyMap());
    }

    public CommandMeta(UserRole requiredRole,
                       boolean cacheable,
                       boolean streamable,
                       boolean requiresAudit,
                       int maxExecutionsPerMinute,
                       boolean dangerous) {
        this(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous, Collections.emptyMap());
    }

    private CommandMeta(UserRole requiredRole,
                        boolean cacheable,
                        boolean streamable,
                        boolean requiresAudit,
                        int maxExecutionsPerMinute,
                        boolean dangerous,
                        Map<String, UserRole> subcommandRoles) {
        this.requiredRole = requiredRole;
        this.cacheable = cacheable;
        this.streamable = streamable;
        this.requiresAudit = requiresAudit;
        this.maxExecutionsPerMinute = maxExecutionsPerMinute;
        this.dangerous = dangerous;
        this.subcommandRoles = subcommandRoles != null ? subcommandRoles : Collections.emptyMap();
    }

    public UserRole getRequiredRole() {
        return requiredRole;
    }

    public UserRole getRequiredRoleForArgs(String[] args) {
        if (args != null && args.length > 1 && !subcommandRoles.isEmpty()) {
            String sub = args[1];
            if (sub != null) {
                UserRole role = subcommandRoles.get(sub.trim().toLowerCase());
                if (role != null) {
                    return role;
                }
            }
        }
        return requiredRole;
    }

    public boolean isCacheable() {
        return cacheable;
    }

    public boolean isStreamable() {
        return streamable;
    }

    public boolean isRequiresAudit() {
        return requiresAudit;
    }

    public int getMaxExecutionsPerMinute() {
        return maxExecutionsPerMinute;
    }

    public boolean isDangerous() {
        return dangerous;
    }

    public CommandMeta withAudit(boolean requiresAudit) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous, subcommandRoles);
    }

    public CommandMeta withRateLimit(int maxExecutionsPerMinute) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous, subcommandRoles);
    }

    public CommandMeta withDangerous(boolean dangerous) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous, subcommandRoles);
    }

    public CommandMeta withSubcommandRole(String subcommand, UserRole role) {
        if (subcommand == null || subcommand.trim().isEmpty() || role == null) {
            return this;
        }
        Map<String, UserRole> next = new HashMap<>(subcommandRoles);
        next.put(subcommand.trim().toLowerCase(), role);
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            Collections.unmodifiableMap(next));
    }

    public static CommandMeta viewer(boolean cacheable, boolean streamable) {
        return new CommandMeta(UserRole.VIEWER, cacheable, streamable);
    }

    public static CommandMeta operator(boolean cacheable, boolean streamable) {
        return new CommandMeta(UserRole.OPERATOR, cacheable, streamable);
    }

    public static CommandMeta admin(boolean cacheable, boolean streamable) {
        return new CommandMeta(UserRole.ADMIN, cacheable, streamable);
    }

    private static boolean defaultRequiresAudit(UserRole role) {
        return role != null && role != UserRole.VIEWER;
    }

    private static int defaultRateLimit(UserRole role) {
        if (role == null) {
            return 60;
        }
        switch (role) {
            case ADMIN:
                return 5;
            case OPERATOR:
                return 20;
            case VIEWER:
            default:
                return 60;
        }
    }
}
