package com.javasleuth.foundation.config;

import java.util.Properties;

/**
 * Fallback defaults used only when bundled default resource cannot be loaded.
 *
 * <p>Prefer {@code /sleuth-default.properties} as the SSOT.</p>
 */
final class DefaultConfigFallback {
    private DefaultConfigFallback() {}

    static void applyFallbackDefaults(Properties properties) {
        SleuthDefaults.apply(properties);
    }
}
