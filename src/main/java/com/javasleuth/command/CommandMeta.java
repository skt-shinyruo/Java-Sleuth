package com.javasleuth.command;

import com.javasleuth.security.AuthenticationManager.UserRole;

public class CommandMeta {
    private final UserRole requiredRole;
    private final boolean cacheable;
    private final boolean streamable;
    private final boolean requiresAudit;
    private final int maxExecutionsPerMinute;
    private final boolean dangerous;

    public CommandMeta(UserRole requiredRole, boolean cacheable, boolean streamable) {
        this(requiredRole, cacheable, streamable,
            defaultRequiresAudit(requiredRole),
            defaultRateLimit(requiredRole),
            false);
    }

    public CommandMeta(UserRole requiredRole,
                       boolean cacheable,
                       boolean streamable,
                       boolean requiresAudit,
                       int maxExecutionsPerMinute,
                       boolean dangerous) {
        this.requiredRole = requiredRole;
        this.cacheable = cacheable;
        this.streamable = streamable;
        this.requiresAudit = requiresAudit;
        this.maxExecutionsPerMinute = maxExecutionsPerMinute;
        this.dangerous = dangerous;
    }

    public UserRole getRequiredRole() {
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
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous);
    }

    public CommandMeta withRateLimit(int maxExecutionsPerMinute) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous);
    }

    public CommandMeta withDangerous(boolean dangerous) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous);
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
