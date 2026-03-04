package com.javasleuth.foundation.security;

import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import com.javasleuth.foundation.security.AuthenticationManager.UserRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限控制管理器：统一基于 {@link CommandMeta} 做角色校验、审计标记与限流。
 *
 * <p>注意：该类不再维护“命令名 → 权限”的第二份映射，避免与 CommandMeta 产生漂移。</p>
 */
public class AuthorizationManager {
    private final ConfigView config;
    private final AuditLogger auditLogger;
    private final AuthenticationManager authManager;

    // Key: sessionId:command -> sliding window timestamps
    private final Map<String, RateLimitInfo> rateLimits = new ConcurrentHashMap<>();

    private static class RateLimitDecision {
        final boolean allowed;
        final int current;

        private RateLimitDecision(boolean allowed, int current) {
            this.allowed = allowed;
            this.current = current;
        }
    }

    private static class RateLimitInfo {
        final List<Long> executions = new ArrayList<>();

        synchronized RateLimitDecision checkRateLimit(int maxPerMinute) {
            if (maxPerMinute <= 0) {
                return new RateLimitDecision(true, 0);
            }
            long now = System.currentTimeMillis();
            long oneMinuteAgo = now - 60000;

            executions.removeIf(time -> time < oneMinuteAgo);
            if (executions.size() >= maxPerMinute) {
                return new RateLimitDecision(false, executions.size());
            }

            executions.add(now);
            return new RateLimitDecision(true, executions.size());
        }
    }

    /**
     * 构造注入路径（推荐）。
     *
     * <p>注意：该构造器不再对 null 依赖做隐式单例回退，以避免“看似 DI、实际全局获取”的不透明依赖来源。</p>
     */
    public AuthorizationManager(ConfigView config, AuditLogger auditLogger, AuthenticationManager authManager) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (auditLogger == null) {
            throw new IllegalArgumentException("auditLogger is required");
        }
        if (authManager == null) {
            throw new IllegalArgumentException("authManager is required");
        }
        this.config = config;
        this.auditLogger = auditLogger;
        this.authManager = authManager;
    }

    /**
     * 默认装配（显式列出依赖来源，避免构造器内部隐式 getInstance 回退）。
     */
    public static AuthorizationManager createDefault() {
        ProductionConfig config = ProductionConfig.createDefault();
        AuditLogger auditLogger = new AuditLogger(config);
        AuthenticationManager authn = new AuthenticationManager(config, auditLogger);
        return new AuthorizationManager(
            config,
            auditLogger,
            authn
        );
    }

    public void shutdown() {
        try {
            rateLimits.clear();
        } catch (Exception ignore) {
            // ignore
        }
    }

    public AuthorizationResult authorize(String sessionId, String command, String[] args, CommandMeta meta) {
        if (!SleuthConfigSchema.SECURITY_AUTHORIZATION_ENABLED.read(config)) {
            return AuthorizationResult.allowed();
        }

        AuthenticationManager.SessionValidationResult sessionResult = authManager.validateSession(sessionId);
        if (!sessionResult.isValid()) {
            return AuthorizationResult.denied("Invalid or expired session");
        }

        if (command == null || command.trim().isEmpty()) {
            return AuthorizationResult.denied("Empty command");
        }
        if (meta == null) {
            auditLogger.logAuthorizationFailure(sessionId, "meta_missing", command, "CommandMeta missing");
            return AuthorizationResult.denied("Command metadata missing: " + command);
        }

        UserRole userRole = sessionResult.getRole();
        UserRole requiredRole = meta.getRequiredRoleForArgs(args);
        if (requiredRole == null) {
            requiredRole = UserRole.ADMIN;
        }
        if (meta.isDangerous() && requiredRole != UserRole.ADMIN) {
            // 全局安全约束：危险命令默认要求管理员权限，避免插件/误配降低门槛。
            requiredRole = UserRole.ADMIN;
        }

        if (!userRole.hasPermission(requiredRole)) {
            auditLogger.logAuthorizationFailure(sessionId, "role", command,
                "Insufficient role: " + userRole.getName() + " < " + requiredRole.getName());
            return AuthorizationResult.denied("Insufficient permissions. Required: " + requiredRole.getName());
        }

        int maxPerMinute = meta.getMaxExecutionsPerMinute();
        String rateLimitKey = sessionId + ":" + command.toLowerCase();
        RateLimitInfo rateLimit = rateLimits.computeIfAbsent(rateLimitKey, k -> new RateLimitInfo());
        RateLimitDecision decision = rateLimit.checkRateLimit(maxPerMinute);
        if (!decision.allowed) {
            auditLogger.logRateLimitViolation(sessionId, null, command, decision.current, maxPerMinute);
            return AuthorizationResult.denied("Rate limit exceeded. Max " + maxPerMinute + " executions per minute");
        }

        if (meta.isRequiresAudit()) {
            auditLogger.logCommandAuthorization(sessionId, command, args, true, userRole.getName());
        }

        return AuthorizationResult.allowed();
    }

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

        public boolean isAllowed() {
            return allowed;
        }

        public String getReason() {
            return reason;
        }
    }
}
