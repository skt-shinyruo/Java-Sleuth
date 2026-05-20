package com.javasleuth.foundation.config.model;

/**
 * 插件相关配置：是否启用、目录、冲突策略与 allowlist。
 */
public final class PluginsConfig {
    public enum ConflictStrategy {
        PREFER_BUILTIN("prefer-builtin"),
        PREFER_PLUGIN("prefer-plugin");

        private final String wireName;

        ConflictStrategy(String wireName) {
            this.wireName = wireName;
        }

        public String getWireName() {
            return wireName;
        }

        public static ConflictStrategy fromWireName(String raw, ConflictStrategy fallback) {
            if (raw == null) {
                return fallback;
            }
            String v = raw.trim().toLowerCase();
            if (v.isEmpty()) {
                return fallback;
            }
            for (ConflictStrategy s : values()) {
                if (s.wireName.equals(v)) {
                    return s;
                }
            }
            return fallback;
        }
    }

    private final boolean enabled;
    private final boolean serviceLoaderEnabled;
    private final String allowlistSha256;
    private final boolean unsafeAllowAllJars;
    private final boolean unsafeLegacyProviderBridgeEnabled;
    private final String directory;
    private final ConflictStrategy conflictStrategy;

    public PluginsConfig(
        boolean enabled,
        boolean serviceLoaderEnabled,
        String allowlistSha256,
        boolean unsafeAllowAllJars,
        boolean unsafeLegacyProviderBridgeEnabled,
        String directory,
        ConflictStrategy conflictStrategy
    ) {
        this.enabled = enabled;
        this.serviceLoaderEnabled = serviceLoaderEnabled;
        this.allowlistSha256 = allowlistSha256;
        this.unsafeAllowAllJars = unsafeAllowAllJars;
        this.unsafeLegacyProviderBridgeEnabled = unsafeLegacyProviderBridgeEnabled;
        this.directory = directory;
        this.conflictStrategy = conflictStrategy;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isServiceLoaderEnabled() {
        return serviceLoaderEnabled;
    }

    public String getAllowlistSha256() {
        return allowlistSha256;
    }

    public boolean isUnsafeAllowAllJars() {
        return unsafeAllowAllJars;
    }

    public boolean isUnsafeLegacyProviderBridgeEnabled() {
        return unsafeLegacyProviderBridgeEnabled;
    }

    public String getDirectory() {
        return directory;
    }

    public ConflictStrategy getConflictStrategy() {
        return conflictStrategy;
    }

    public String getConflictStrategyWireName() {
        return conflictStrategy != null ? conflictStrategy.getWireName() : ConflictStrategy.PREFER_BUILTIN.getWireName();
    }
}
