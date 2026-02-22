package com.javasleuth.foundation.security;

import com.javasleuth.foundation.security.AuthenticationManager.UserRole;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class CommandMeta {
    public enum ImpactLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    private final UserRole requiredRole;
    private final boolean cacheable;
    private final boolean streamable;
    private final boolean requiresAudit;
    private final int maxExecutionsPerMinute;
    private final boolean dangerous;
    private final ImpactLevel impact;
    private final Map<String, UserRole> subcommandRoles;

    public CommandMeta(UserRole requiredRole, boolean cacheable, boolean streamable) {
        this(requiredRole, cacheable, streamable,
            defaultRequiresAudit(requiredRole),
            defaultRateLimit(requiredRole),
            false,
            defaultImpact(false),
            Collections.emptyMap());
    }

    public CommandMeta(UserRole requiredRole,
                       boolean cacheable,
                       boolean streamable,
                       boolean requiresAudit,
                       int maxExecutionsPerMinute,
                       boolean dangerous) {
        this(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            defaultImpact(dangerous),
            Collections.emptyMap());
    }

    private CommandMeta(UserRole requiredRole,
                        boolean cacheable,
                        boolean streamable,
                        boolean requiresAudit,
                        int maxExecutionsPerMinute,
                        boolean dangerous,
                        ImpactLevel impact,
                        Map<String, UserRole> subcommandRoles) {
        this.requiredRole = requiredRole;
        this.cacheable = cacheable;
        this.streamable = streamable;
        this.requiresAudit = requiresAudit;
        this.maxExecutionsPerMinute = maxExecutionsPerMinute;
        this.dangerous = dangerous;
        this.impact = impact != null ? impact : ImpactLevel.LOW;
        this.subcommandRoles = subcommandRoles != null ? subcommandRoles : Collections.emptyMap();
    }

    public UserRole getRequiredRole() {
        return requiredRole;
    }

    public UserRole getRequiredRoleForArgs(String[] args) {
        if (args != null && args.length > 1 && !subcommandRoles.isEmpty()) {
            String sub = args[1];
            if (sub != null) {
                UserRole role = subcommandRoles.get(sub.trim().toLowerCase(Locale.ROOT));
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

    public ImpactLevel getImpactLevel() {
        return impact;
    }

    public CommandMeta withAudit(boolean requiresAudit) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous, impact, subcommandRoles);
    }

    public CommandMeta withRateLimit(int maxExecutionsPerMinute) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous, impact, subcommandRoles);
    }

    public CommandMeta withDangerous(boolean dangerous) {
        ImpactLevel nextImpact = impact;
        if (dangerous && (nextImpact == null || nextImpact == ImpactLevel.LOW)) {
            nextImpact = ImpactLevel.HIGH;
        }
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous, nextImpact, subcommandRoles);
    }

    public CommandMeta withImpact(ImpactLevel impact) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact != null ? impact : ImpactLevel.LOW,
            subcommandRoles);
    }

    public CommandMeta withSubcommandRole(String subcommand, UserRole role) {
        if (subcommand == null || subcommand.trim().isEmpty() || role == null) {
            return this;
        }
        Map<String, UserRole> next = new HashMap<>(subcommandRoles);
        next.put(subcommand.trim().toLowerCase(Locale.ROOT), role);
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous, impact,
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

    private static ImpactLevel defaultImpact(boolean dangerous) {
        return dangerous ? ImpactLevel.HIGH : ImpactLevel.LOW;
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
