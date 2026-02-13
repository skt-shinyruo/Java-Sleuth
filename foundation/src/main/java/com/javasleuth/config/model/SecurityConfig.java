package com.javasleuth.config.model;

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
    private final boolean authorizationEnabled;
    private final boolean anonymousViewerEnabled;
    private final String hmacSessionRole;

    public SecurityConfig(Mode mode, boolean authorizationEnabled, boolean anonymousViewerEnabled, String hmacSessionRole) {
        this.mode = mode;
        this.authorizationEnabled = authorizationEnabled;
        this.anonymousViewerEnabled = anonymousViewerEnabled;
        this.hmacSessionRole = hmacSessionRole;
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

    public boolean isAuthorizationEnabled() {
        return authorizationEnabled;
    }

    public boolean isAnonymousViewerEnabled() {
        return anonymousViewerEnabled;
    }

    public String getHmacSessionRole() {
        return hmacSessionRole;
    }
}

