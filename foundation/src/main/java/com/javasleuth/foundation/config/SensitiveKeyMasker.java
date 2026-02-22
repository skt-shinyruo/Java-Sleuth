package com.javasleuth.foundation.config;

import java.util.Locale;

/**
 * Centralized masking strategy for sensitive configuration values.
 */
public final class SensitiveKeyMasker {
    public boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("password") ||
            k.contains("secret") ||
            k.contains("token") ||
            k.contains("credential") ||
            k.contains("session") ||
            k.contains("apikey") ||
            k.contains("api_key") ||
            k.contains("key");
    }

    public String mask(String key, String value) {
        if (value == null) {
            return "null";
        }
        if (!isSensitiveKey(key)) {
            return value;
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }
}
