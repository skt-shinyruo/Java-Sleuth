package com.javasleuth.foundation.config;

/**
 * Indicates where a configuration value is resolved from (read-time origin).
 *
 * Priority (high -> low):
 * RUNTIME_OVERRIDE > SYSTEM_PROPERTY > FILE > DEFAULT
 */
public enum ConfigOrigin {
    RUNTIME_OVERRIDE,
    SYSTEM_PROPERTY,
    FILE,
    DEFAULT,
    UNKNOWN
}
