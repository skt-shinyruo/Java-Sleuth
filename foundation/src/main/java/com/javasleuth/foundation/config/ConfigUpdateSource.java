package com.javasleuth.foundation.config;

/**
 * Indicates who/what triggered a runtime configuration change (write-time source).
 */
public enum ConfigUpdateSource {
    COMMAND,
    BOOTSTRAP,
    INTERNAL,
    TEST,
    UNKNOWN
}
