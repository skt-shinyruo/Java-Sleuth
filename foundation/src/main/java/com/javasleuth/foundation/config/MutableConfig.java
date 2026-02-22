package com.javasleuth.foundation.config;

/**
 * Narrow writable interface for runtime overrides.
 *
 * <p>Write operations are expected to be audited via {@link RuntimeConfigStore}.</p>
 */
public interface MutableConfig {
    void setRuntimeConfig(String key, String value, ConfigUpdateSource source);

    void removeRuntimeConfig(String key, ConfigUpdateSource source);

    void clearRuntimeConfig(ConfigUpdateSource source);
}
