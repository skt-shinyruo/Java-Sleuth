package com.javasleuth.foundation.config.schema;

public final class ConfigValidationResult {
    private final boolean valid;
    private final String normalizedValue;
    private final String error;
    private final boolean sensitive;

    private ConfigValidationResult(boolean valid, String normalizedValue, String error, boolean sensitive) {
        this.valid = valid;
        this.normalizedValue = normalizedValue;
        this.error = error;
        this.sensitive = sensitive;
    }

    public static ConfigValidationResult ok(String normalizedValue, boolean sensitive) {
        return new ConfigValidationResult(true, normalizedValue, null, sensitive);
    }

    public static ConfigValidationResult invalid(String error, boolean sensitive) {
        return new ConfigValidationResult(false, null, error, sensitive);
    }

    public boolean isValid() {
        return valid;
    }

    public String getNormalizedValue() {
        return normalizedValue;
    }

    public String getError() {
        return error;
    }

    public boolean isSensitive() {
        return sensitive;
    }
}
