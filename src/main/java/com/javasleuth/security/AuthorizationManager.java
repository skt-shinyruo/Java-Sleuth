package com.javasleuth.security;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.command.CommandMeta;
import com.javasleuth.security.AuthenticationManager.UserRole;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Role-based access control manager for Java-Sleuth commands
 */
public class AuthorizationManager {
    private static AuthorizationManager instance;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final AuthenticationManager authManager;

    // Permission definitions
    private final Map<String, CommandPermission> commandPermissions = new ConcurrentHashMap<>();
    private final Map<UserRole, Set<String>> rolePermissions = new ConcurrentHashMap<>();

    // Rate limiting
    private final Map<String, RateLimitInfo> rateLimits = new ConcurrentHashMap<>();

    private static class CommandPermission {
        final UserRole minimumRole;
        final boolean requiresAudit;
        final int maxExecutionsPerMinute;
        final boolean dangerous;

        CommandPermission(UserRole minimumRole, boolean requiresAudit, int maxExecutionsPerMinute, boolean dangerous) {
            this.minimumRole = minimumRole;
            this.requiresAudit = requiresAudit;
            this.maxExecutionsPerMinute = maxExecutionsPerMinute;
            this.dangerous = dangerous;
        }
    }

    private static class RateLimitInfo {
        final List<Long> executions = new ArrayList<>();

        synchronized boolean checkRateLimit(int maxPerMinute) {
            long now = System.currentTimeMillis();
            long oneMinuteAgo = now - 60000;

            // Remove old executions
            executions.removeIf(time -> time < oneMinuteAgo);

            if (executions.size() >= maxPerMinute) {
                return false; // Rate limit exceeded
            }

            executions.add(now);
            return true;
        }
    }

    private AuthorizationManager() {
        this.config = ProductionConfig.getInstance();
        this.auditLogger = AuditLogger.getInstance();
        this.authManager = AuthenticationManager.getInstance();

        initializePermissions();
    }

    public static synchronized AuthorizationManager getInstance() {
        if (instance == null) {
            instance = new AuthorizationManager();
        }
        return instance;
    }

    /**
     * Initialize command permissions and role mappings
     */
    private void initializePermissions() {
        // Viewer commands - safe read-only operations
        addPermission("help", UserRole.VIEWER, false, 60, false);
        addPermission("dashboard", UserRole.VIEWER, false, 30, false);
        addPermission("thread", UserRole.VIEWER, false, 20, false);
        addPermission("sc", UserRole.VIEWER, false, 20, false);
        addPermission("sm", UserRole.VIEWER, false, 20, false);
        addPermission("jvm", UserRole.VIEWER, false, 10, false);
        addPermission("sysprop", UserRole.VIEWER, false, 10, false);
        addPermission("sysenv", UserRole.VIEWER, false, 10, false);
        addPermission("memory", UserRole.VIEWER, false, 10, false);
        addPermission("classloader", UserRole.VIEWER, false, 10, false);
        addPermission("health", UserRole.VIEWER, false, 20, false);
        addPermission("status", UserRole.VIEWER, false, 20, false);
        addPermission("metrics", UserRole.VIEWER, false, 10, false);

        // Operator commands - monitoring and profiling
        addPermission("watch", UserRole.OPERATOR, true, 10, false);
        addPermission("trace", UserRole.OPERATOR, true, 10, false);
        addPermission("monitor", UserRole.OPERATOR, true, 10, false);
        addPermission("profiler", UserRole.OPERATOR, true, 5, false);
        addPermission("stack", UserRole.OPERATOR, false, 10, false);
        addPermission("jad", UserRole.OPERATOR, true, 5, false);
        addPermission("mbean", UserRole.OPERATOR, true, 10, false);
        addPermission("vmoption", UserRole.OPERATOR, true, 5, false);

        // Admin commands - dangerous operations
        addPermission("redefine", UserRole.ADMIN, true, 3, true);
        addPermission("retransform", UserRole.ADMIN, true, 3, true);
        addPermission("mc", UserRole.ADMIN, true, 3, true);
        addPermission("heapdump", UserRole.ADMIN, true, 2, true);
        addPermission("config", UserRole.ADMIN, true, 5, false);
        addPermission("audit", UserRole.ADMIN, false, 10, false);

        // System commands
        addPermission("quit", UserRole.VIEWER, false, 5, false);
    }

    private void addPermission(String command, UserRole role, boolean audit, int rateLimit, boolean dangerous) {
        commandPermissions.put(command.toLowerCase(),
            new CommandPermission(role, audit, rateLimit, dangerous));
    }

    /**
     * Register/refresh command permissions dynamically (e.g. plugin commands).
     * Merges with existing permissions using a "most restrictive wins" strategy.
     */
    public void registerOrUpdatePermission(String commandName, CommandMeta meta) {
        if (commandName == null || meta == null) {
            return;
        }
        registerOrUpdatePermission(
            commandName,
            meta.getRequiredRole(),
            meta.isRequiresAudit(),
            meta.getMaxExecutionsPerMinute(),
            meta.isDangerous()
        );
    }

    public void registerOrUpdatePermission(String commandName,
                                           UserRole minimumRole,
                                           boolean requiresAudit,
                                           int maxExecutionsPerMinute,
                                           boolean dangerous) {
        if (commandName == null || commandName.trim().isEmpty() || minimumRole == null) {
            return;
        }
        String key = commandName.toLowerCase();
        int safeRateLimit = maxExecutionsPerMinute > 0 ? maxExecutionsPerMinute : 60;

        UserRole effectiveRole = minimumRole;
        if (dangerous && effectiveRole.getLevel() < UserRole.ADMIN.getLevel()) {
            effectiveRole = UserRole.ADMIN;
        }
        final UserRole finalRole = effectiveRole;
        final boolean finalAudit = requiresAudit;
        final boolean finalDangerous = dangerous;
        final int finalRateLimit = safeRateLimit;

        commandPermissions.compute(key, (k, existing) -> {
            if (existing == null) {
                return new CommandPermission(finalRole, finalAudit, finalRateLimit, finalDangerous);
            }

            UserRole mergedRole = existing.minimumRole;
            if (finalRole.getLevel() > existing.minimumRole.getLevel()) {
                mergedRole = finalRole;
            }

            boolean mergedAudit = existing.requiresAudit || finalAudit;
            boolean mergedDangerous = existing.dangerous || finalDangerous;

            int mergedRate;
            if (existing.maxExecutionsPerMinute <= 0) {
                mergedRate = finalRateLimit;
            } else if (finalRateLimit <= 0) {
                mergedRate = existing.maxExecutionsPerMinute;
            } else {
                mergedRate = Math.min(existing.maxExecutionsPerMinute, finalRateLimit);
            }

            return new CommandPermission(mergedRole, mergedAudit, mergedRate, mergedDangerous);
        });
    }

    /**
     * Check if user has permission to execute command
     */
    public AuthorizationResult authorize(String sessionId, String command, String[] args) {
        if (!config.isAuthorizationEnabled()) {
            return AuthorizationResult.allowed();
        }
        // Validate session first
        AuthenticationManager.SessionValidationResult sessionResult = authManager.validateSession(sessionId);
        if (!sessionResult.isValid()) {
            return AuthorizationResult.denied("Invalid or expired session");
        }

        UserRole userRole = sessionResult.getRole();
        CommandPermission permission = commandPermissions.get(command.toLowerCase());

        if (permission == null) {
            auditLogger.logAuthorizationFailure(sessionId, "unknown", command, "Unknown command");
            return AuthorizationResult.denied("Unknown command: " + command);
        }

        // Check role permission
        if (!userRole.hasPermission(permission.minimumRole)) {
            auditLogger.logAuthorizationFailure(sessionId, "role", command,
                "Insufficient role: " + userRole.getName() + " < " + permission.minimumRole.getName());
            return AuthorizationResult.denied("Insufficient permissions. Required: " + permission.minimumRole.getName());
        }

        // Check rate limiting
        String rateLimitKey = sessionId + ":" + command;
        RateLimitInfo rateLimit = rateLimits.computeIfAbsent(rateLimitKey, k -> new RateLimitInfo());

        if (!rateLimit.checkRateLimit(permission.maxExecutionsPerMinute)) {
            auditLogger.logSecurityViolation(sessionId, "rate_limit", "RATE_LIMIT_EXCEEDED",
                "Command " + command + " rate limit exceeded");
            return AuthorizationResult.denied("Rate limit exceeded. Max " + permission.maxExecutionsPerMinute + " executions per minute");
        }

        // Check for dangerous operations
        if (permission.dangerous) {
            AuthorizationResult dangerousCheck = checkDangerousOperation(sessionId, userRole, command, args);
            if (!dangerousCheck.isAllowed()) {
                return dangerousCheck;
            }
        }

        // Command-specific authorization
        AuthorizationResult specificCheck = checkCommandSpecificAuthorization(sessionId, userRole, command, args);
        if (!specificCheck.isAllowed()) {
            return specificCheck;
        }

        // Log if audit required
        if (permission.requiresAudit) {
            auditLogger.logCommandAuthorization(sessionId, command, args, true, userRole.getName());
        }

        return AuthorizationResult.allowed();
    }

    /**
     * Additional checks for dangerous operations
     */
    private AuthorizationResult checkDangerousOperation(String sessionId, UserRole userRole, String command, String[] args) {
        switch (command.toLowerCase()) {
            case "redefine":
            case "retransform":
                // Warn about class modification
                auditLogger.logSecurityViolation(sessionId, "dangerous_operation", "CLASS_MODIFICATION",
                    "User " + userRole.getName() + " performing class modification: " + command);
                break;

            case "heapdump":
                // Heap dumps can contain sensitive data
                auditLogger.logSecurityViolation(sessionId, "dangerous_operation", "HEAP_DUMP",
                    "User " + userRole.getName() + " creating heap dump");

                // Check if path is safe
                if (args.length > 1) {
                    String path = args[1];
                    if (path.contains("/etc/") || path.contains("/proc/") || path.contains("/sys/")) {
                        return AuthorizationResult.denied("Heap dump path not allowed in system directories");
                    }
                }
                break;

            case "mc":
                // Memory compilation can be dangerous
                auditLogger.logSecurityViolation(sessionId, "dangerous_operation", "MEMORY_COMPILE",
                    "User " + userRole.getName() + " compiling code in memory");
                break;
        }

        return AuthorizationResult.allowed();
    }

    /**
     * Command-specific authorization logic
     */
    private AuthorizationResult checkCommandSpecificAuthorization(String sessionId, UserRole userRole, String command, String[] args) {
        switch (command.toLowerCase()) {
            case "watch":
            case "trace":
                // Limit watching system classes for non-admin users
                if (userRole != UserRole.ADMIN && args.length > 1) {
                    String className = args[1];
                    if (className.startsWith("java.lang.") || className.startsWith("sun.") || className.startsWith("com.sun.")) {
                        auditLogger.logAuthorizationFailure(sessionId, "system_class", command,
                            "Non-admin user attempting to watch system class: " + className);
                        return AuthorizationResult.denied("Watching system classes requires admin privileges");
                    }
                }
                break;

            case "profiler":
                // Profiler can impact performance
                if (args.length > 1 && "start".equals(args[1]) && userRole == UserRole.OPERATOR) {
                    auditLogger.logSystemEvent("PROFILER_START",
                        "Operator " + sessionId + " starting profiler - monitor performance impact");
                }
                break;

            case "config":
                // Only admins can modify configuration
                if (args.length > 2 && userRole != UserRole.ADMIN) {
                    return AuthorizationResult.denied("Configuration modification requires admin privileges");
                }
                break;
        }

        return AuthorizationResult.allowed();
    }

    /**
     * Get user permissions summary
     */
    public String getPermissionsSummary(String sessionId) {
        AuthenticationManager.SessionValidationResult sessionResult = authManager.validateSession(sessionId);
        if (!sessionResult.isValid()) {
            return "Invalid session";
        }

        UserRole userRole = sessionResult.getRole();
        StringBuilder summary = new StringBuilder();

        summary.append("=== USER PERMISSIONS ===\n");
        summary.append("Role: ").append(userRole.getName().toUpperCase()).append("\n");
        summary.append("Level: ").append(userRole.getLevel()).append("\n\n");

        summary.append("-- Allowed Commands --\n");
        commandPermissions.entrySet().stream()
            .filter(entry -> userRole.hasPermission(entry.getValue().minimumRole))
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                String cmd = entry.getKey();
                CommandPermission perm = entry.getValue();
                summary.append(String.format("%-15s: %s%s%s\n",
                    cmd,
                    perm.dangerous ? "⚠️ " : "",
                    perm.requiresAudit ? "📝 " : "",
                    "(" + perm.maxExecutionsPerMinute + "/min)"));
            });

        summary.append("\n-- Legend --\n");
        summary.append("⚠️  Dangerous operation\n");
        summary.append("📝 Audited command\n");

        return summary.toString();
    }

    /**
     * Get rate limit status
     */
    public String getRateLimitStatus(String sessionId) {
        StringBuilder status = new StringBuilder();
        status.append("=== RATE LIMIT STATUS ===\n");

        rateLimits.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(sessionId + ":"))
            .forEach(entry -> {
                String command = entry.getKey().substring(sessionId.length() + 1);
                RateLimitInfo info = entry.getValue();
                CommandPermission perm = commandPermissions.get(command);

                if (perm != null) {
                    synchronized (info) {
                        long now = System.currentTimeMillis();
                        long oneMinuteAgo = now - 60000;
                        int currentExecutions = (int) info.executions.stream()
                            .filter(time -> time > oneMinuteAgo)
                            .count();

                        status.append(String.format("%-15s: %d/%d executions\n",
                            command, currentExecutions, perm.maxExecutionsPerMinute));
                    }
                }
            });

        return status.toString();
    }

    /**
     * Emergency: disable all dangerous commands
     */
    public void emergencyLockdown() {
        commandPermissions.entrySet().stream()
            .filter(entry -> entry.getValue().dangerous)
            .forEach(entry -> {
                String command = entry.getKey();
                // Temporarily set to require ADMIN role with rate limit 0
                commandPermissions.put(command,
                    new CommandPermission(UserRole.ADMIN, true, 0, true));
            });

        auditLogger.logSystemEvent("EMERGENCY_LOCKDOWN", "All dangerous commands disabled");
        System.out.println("EMERGENCY LOCKDOWN: All dangerous commands have been disabled");
    }

    // Result class
    public static class AuthorizationResult {
        private final boolean allowed;
        private final String reason;

        private AuthorizationResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static AuthorizationResult allowed() {
            return new AuthorizationResult(true, null);
        }

        public static AuthorizationResult denied(String reason) {
            return new AuthorizationResult(false, reason);
        }

        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
    }
}
