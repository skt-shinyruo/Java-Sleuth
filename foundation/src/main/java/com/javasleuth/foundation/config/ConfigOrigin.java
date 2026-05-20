package com.javasleuth.foundation.config;

/**
 * Indicates where a configuration value is resolved from.
 *
 * Priority (high -> low):
 * RUNTIME_OVERRIDE > SYSTEM_PROPERTY > FILE > DEFAULT
 *
 * <p>{@link #SYSTEM_PROPERTY} means a {@code sleuth.*} system property captured during configuration
 * load/reload, not a live read of global {@link System} properties on every access.</p>
 */
public enum ConfigOrigin {
    RUNTIME_OVERRIDE,
    SYSTEM_PROPERTY,
    FILE,
    DEFAULT,
    UNKNOWN
}
