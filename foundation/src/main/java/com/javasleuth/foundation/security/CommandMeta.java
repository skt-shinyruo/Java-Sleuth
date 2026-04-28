package com.javasleuth.foundation.security;

import com.javasleuth.foundation.security.AuthenticationManager.UserRole;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private final Set<CommandCapability> capabilities;
    private final Set<String> requiredBootstrapClasses;

    public CommandMeta(UserRole requiredRole, boolean cacheable, boolean streamable) {
        this(requiredRole, cacheable, streamable,
            defaultRequiresAudit(requiredRole),
            defaultRateLimit(requiredRole),
            false,
            defaultImpact(false),
            Collections.emptyMap(),
            Collections.<CommandCapability>emptySet(),
            Collections.<String>emptySet());
    }

    public CommandMeta(UserRole requiredRole,
                       boolean cacheable,
                       boolean streamable,
                       boolean requiresAudit,
                       int maxExecutionsPerMinute,
                       boolean dangerous) {
        this(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            defaultImpact(dangerous),
            Collections.emptyMap(),
            Collections.<CommandCapability>emptySet(),
            Collections.<String>emptySet());
    }

    private CommandMeta(UserRole requiredRole,
                        boolean cacheable,
                        boolean streamable,
                        boolean requiresAudit,
                        int maxExecutionsPerMinute,
                        boolean dangerous,
                        ImpactLevel impact,
                        Map<String, UserRole> subcommandRoles,
                        Set<CommandCapability> capabilities,
                        Set<String> requiredBootstrapClasses) {
        this.requiredRole = requiredRole;
        this.cacheable = cacheable;
        this.streamable = streamable;
        this.requiresAudit = requiresAudit;
        this.maxExecutionsPerMinute = maxExecutionsPerMinute;
        this.dangerous = dangerous;
        this.impact = impact != null ? impact : ImpactLevel.LOW;
        this.subcommandRoles = subcommandRoles != null ? subcommandRoles : Collections.emptyMap();
        this.capabilities = immutableCapabilities(capabilities);
        this.requiredBootstrapClasses = immutableBootstrapClasses(requiredBootstrapClasses);
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

    public boolean hasCapability(CommandCapability capability) {
        return capability != null && capabilities.contains(capability);
    }

    public Set<CommandCapability> getCapabilities() {
        return capabilities;
    }

    public boolean requiresBootstrap() {
        return !requiredBootstrapClasses.isEmpty();
    }

    public Set<String> getRequiredBootstrapClasses() {
        return requiredBootstrapClasses;
    }

    public CommandMeta withAudit(boolean requiresAudit) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact, subcommandRoles, capabilities, requiredBootstrapClasses);
    }

    public CommandMeta withRateLimit(int maxExecutionsPerMinute) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact, subcommandRoles, capabilities, requiredBootstrapClasses);
    }

    public CommandMeta withCapability(CommandCapability capability) {
        if (capability == null) {
            return this;
        }
        Set<CommandCapability> next = new LinkedHashSet<>(capabilities);
        next.add(capability);
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact, subcommandRoles, next, requiredBootstrapClasses);
    }

    public CommandMeta withCapabilities(Collection<CommandCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return this;
        }
        Set<CommandCapability> next = new LinkedHashSet<>(this.capabilities);
        for (CommandCapability c : capabilities) {
            if (c != null) {
                next.add(c);
            }
        }
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact, subcommandRoles, next, requiredBootstrapClasses);
    }

    public CommandMeta requiresBootstrap(String binaryClassName) {
        if (binaryClassName == null || binaryClassName.trim().isEmpty()) {
            return this;
        }
        Set<String> nextBootstrap = new LinkedHashSet<>(requiredBootstrapClasses);
        nextBootstrap.add(binaryClassName.trim());
        Set<CommandCapability> nextCapabilities = new LinkedHashSet<>(capabilities);
        nextCapabilities.add(CommandCapability.USES_INSTRUMENTATION);
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact, subcommandRoles, nextCapabilities, nextBootstrap);
    }

    public CommandMeta requiresBootstrap(Collection<String> binaryClassNames) {
        if (binaryClassNames == null || binaryClassNames.isEmpty()) {
            return this;
        }
        CommandMeta next = this;
        for (String name : binaryClassNames) {
            next = next.requiresBootstrap(name);
        }
        return next;
    }

    public CommandMeta withDangerous(boolean dangerous) {
        ImpactLevel nextImpact = impact;
        if (dangerous && (nextImpact == null || nextImpact == ImpactLevel.LOW)) {
            nextImpact = ImpactLevel.HIGH;
        }
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            nextImpact, subcommandRoles, capabilities, requiredBootstrapClasses);
    }

    public CommandMeta withImpact(ImpactLevel impact) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact != null ? impact : ImpactLevel.LOW,
            subcommandRoles, capabilities, requiredBootstrapClasses);
    }

    public CommandMeta withSubcommandRole(String subcommand, UserRole role) {
        if (subcommand == null || subcommand.trim().isEmpty() || role == null) {
            return this;
        }
        Map<String, UserRole> next = new HashMap<>(subcommandRoles);
        next.put(subcommand.trim().toLowerCase(Locale.ROOT), role);
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous, impact,
            Collections.unmodifiableMap(next), capabilities, requiredBootstrapClasses);
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

    private static Set<CommandCapability> immutableCapabilities(Set<CommandCapability> input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptySet();
        }
        Set<CommandCapability> out = new LinkedHashSet<>();
        for (CommandCapability c : input) {
            if (c != null) {
                out.add(c);
            }
        }
        return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
    }

    private static Set<String> immutableBootstrapClasses(Set<String> input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : input) {
            if (s == null) {
                continue;
            }
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
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
