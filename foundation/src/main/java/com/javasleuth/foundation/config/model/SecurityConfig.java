package com.javasleuth.foundation.config.model;

/**
 * 安全相关配置（传输/会话模式、授权开关、匿名策略等）。
 */
public final class SecurityConfig {
    public enum Mode {
        OFF("off"),
        HMAC("hmac");

        private final String wireName;

        Mode(String wireName) {
            this.wireName = wireName;
        }

        public String getWireName() {
            return wireName;
        }
    }

    private final Mode mode;
    private final boolean inputValidationEnabled;
    private final boolean auditLoggingEnabled;
    private final boolean authorizationEnabled;
    private final boolean anonymousViewerEnabled;
    private final int maxCommandLength;
    private final String allowedCommands;

    private final String hmacSecret;
    private final boolean hmacSecretAutogenOnLoopbackEnabled;
    private final boolean hmacSecretAutogenPrintEnabled;
    private final long hmacTimestampWindowMs;
    private final int hmacNonceCacheSize;

    private final boolean dangerousConfirmEnabled;
    private final long dangerousConfirmTtlMs;
    private final int dangerousConfirmTokenBytes;
    private final int dangerousConfirmCacheSize;

    private final boolean impactHighConfirmEnabled;
    private final int impactHighConcurrentLimit;

    private final boolean hmacBootstrapOnAttachEnabled;
    private final int hmacBootstrapSecretBytes;
    private final String hmacSessionRole;

    private final boolean passwordAuthEnabled;
    private final String authAdminPassword;
    private final String authOperatorPassword;
    private final String authViewerPassword;

    public SecurityConfig(
        Mode mode,
        boolean inputValidationEnabled,
        boolean auditLoggingEnabled,
        boolean authorizationEnabled,
        boolean anonymousViewerEnabled,
        int maxCommandLength,
        String allowedCommands,
        String hmacSecret,
        boolean hmacSecretAutogenOnLoopbackEnabled,
        boolean hmacSecretAutogenPrintEnabled,
        long hmacTimestampWindowMs,
        int hmacNonceCacheSize,
        boolean dangerousConfirmEnabled,
        long dangerousConfirmTtlMs,
        int dangerousConfirmTokenBytes,
        int dangerousConfirmCacheSize,
        boolean impactHighConfirmEnabled,
        int impactHighConcurrentLimit,
        boolean hmacBootstrapOnAttachEnabled,
        int hmacBootstrapSecretBytes,
        String hmacSessionRole,
        boolean passwordAuthEnabled,
        String authAdminPassword,
        String authOperatorPassword,
        String authViewerPassword
    ) {
        this.mode = mode;
        this.inputValidationEnabled = inputValidationEnabled;
        this.auditLoggingEnabled = auditLoggingEnabled;
        this.authorizationEnabled = authorizationEnabled;
        this.anonymousViewerEnabled = anonymousViewerEnabled;
        this.maxCommandLength = maxCommandLength;
        this.allowedCommands = allowedCommands;

        this.hmacSecret = hmacSecret;
        this.hmacSecretAutogenOnLoopbackEnabled = hmacSecretAutogenOnLoopbackEnabled;
        this.hmacSecretAutogenPrintEnabled = hmacSecretAutogenPrintEnabled;
        this.hmacTimestampWindowMs = hmacTimestampWindowMs;
        this.hmacNonceCacheSize = hmacNonceCacheSize;

        this.dangerousConfirmEnabled = dangerousConfirmEnabled;
        this.dangerousConfirmTtlMs = dangerousConfirmTtlMs;
        this.dangerousConfirmTokenBytes = dangerousConfirmTokenBytes;
        this.dangerousConfirmCacheSize = dangerousConfirmCacheSize;

        this.impactHighConfirmEnabled = impactHighConfirmEnabled;
        this.impactHighConcurrentLimit = impactHighConcurrentLimit;

        this.hmacBootstrapOnAttachEnabled = hmacBootstrapOnAttachEnabled;
        this.hmacBootstrapSecretBytes = hmacBootstrapSecretBytes;
        this.hmacSessionRole = hmacSessionRole;

        this.passwordAuthEnabled = passwordAuthEnabled;
        this.authAdminPassword = authAdminPassword;
        this.authOperatorPassword = authOperatorPassword;
        this.authViewerPassword = authViewerPassword;
    }

    public Mode getMode() {
        return mode;
    }

    public String getModeWireName() {
        return mode != null ? mode.getWireName() : "off";
    }

    public boolean isHmacEnabled() {
        return mode == Mode.HMAC;
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

    public String getHmacSecret() {
        return hmacSecret;
    }

    public boolean isHmacSecretAutogenOnLoopbackEnabled() {
        return hmacSecretAutogenOnLoopbackEnabled;
    }

    public boolean isHmacSecretAutogenPrintEnabled() {
        return hmacSecretAutogenPrintEnabled;
    }

    public long getHmacTimestampWindowMs() {
        return hmacTimestampWindowMs;
    }

    public int getHmacNonceCacheSize() {
        return hmacNonceCacheSize;
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

    public boolean isHmacBootstrapOnAttachEnabled() {
        return hmacBootstrapOnAttachEnabled;
    }

    public int getHmacBootstrapSecretBytes() {
        return hmacBootstrapSecretBytes;
    }

    public String getHmacSessionRole() {
        return hmacSessionRole;
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
