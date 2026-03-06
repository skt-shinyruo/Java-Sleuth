package com.javasleuth.foundation.config.model;

/**
 * 安全相关配置（输入校验、审计、认证授权等）。
 */
public final class SecurityConfig {
    private final boolean inputValidationEnabled;
    private final boolean auditLoggingEnabled;
    private final boolean authorizationEnabled;
    private final boolean anonymousViewerEnabled;
    private final int maxCommandLength;
    private final String allowedCommands;

    private final boolean dangerousConfirmEnabled;
    private final long dangerousConfirmTtlMs;
    private final int dangerousConfirmTokenBytes;
    private final int dangerousConfirmCacheSize;

    private final boolean impactHighConfirmEnabled;
    private final int impactHighConcurrentLimit;

    private final boolean passwordAuthEnabled;
    private final String authAdminPassword;
    private final String authOperatorPassword;
    private final String authViewerPassword;

    public SecurityConfig(
        boolean inputValidationEnabled,
        boolean auditLoggingEnabled,
        boolean authorizationEnabled,
        boolean anonymousViewerEnabled,
        int maxCommandLength,
        String allowedCommands,
        boolean dangerousConfirmEnabled,
        long dangerousConfirmTtlMs,
        int dangerousConfirmTokenBytes,
        int dangerousConfirmCacheSize,
        boolean impactHighConfirmEnabled,
        int impactHighConcurrentLimit,
        boolean passwordAuthEnabled,
        String authAdminPassword,
        String authOperatorPassword,
        String authViewerPassword
    ) {
        this.inputValidationEnabled = inputValidationEnabled;
        this.auditLoggingEnabled = auditLoggingEnabled;
        this.authorizationEnabled = authorizationEnabled;
        this.anonymousViewerEnabled = anonymousViewerEnabled;
        this.maxCommandLength = maxCommandLength;
        this.allowedCommands = allowedCommands;

        this.dangerousConfirmEnabled = dangerousConfirmEnabled;
        this.dangerousConfirmTtlMs = dangerousConfirmTtlMs;
        this.dangerousConfirmTokenBytes = dangerousConfirmTokenBytes;
        this.dangerousConfirmCacheSize = dangerousConfirmCacheSize;

        this.impactHighConfirmEnabled = impactHighConfirmEnabled;
        this.impactHighConcurrentLimit = impactHighConcurrentLimit;

        this.passwordAuthEnabled = passwordAuthEnabled;
        this.authAdminPassword = authAdminPassword;
        this.authOperatorPassword = authOperatorPassword;
        this.authViewerPassword = authViewerPassword;
    }

    public boolean isInputValidationEnabled() {
        return inputValidationEnabled;
    }

    public boolean isAuditLoggingEnabled() {
        return auditLoggingEnabled;
    }

    public boolean isAuthorizationEnabled() {
        return authorizationEnabled;
    }

    public boolean isAnonymousViewerEnabled() {
        return anonymousViewerEnabled;
    }

    public int getMaxCommandLength() {
        return maxCommandLength;
    }

    public String getAllowedCommands() {
        return allowedCommands;
    }

    public boolean isDangerousConfirmEnabled() {
        return dangerousConfirmEnabled;
    }

    public long getDangerousConfirmTtlMs() {
        return dangerousConfirmTtlMs;
    }

    public int getDangerousConfirmTokenBytes() {
        return dangerousConfirmTokenBytes;
    }

    public int getDangerousConfirmCacheSize() {
        return dangerousConfirmCacheSize;
    }

    public boolean isImpactHighConfirmEnabled() {
        return impactHighConfirmEnabled;
    }

    public int getImpactHighConcurrentLimit() {
        return impactHighConcurrentLimit;
    }

    public boolean isPasswordAuthEnabled() {
        return passwordAuthEnabled;
    }

    public String getAuthAdminPassword() {
        return authAdminPassword;
    }

    public String getAuthOperatorPassword() {
        return authOperatorPassword;
    }

    public String getAuthViewerPassword() {
        return authViewerPassword;
    }
}
