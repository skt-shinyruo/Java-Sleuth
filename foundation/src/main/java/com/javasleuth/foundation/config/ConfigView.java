package com.javasleuth.foundation.config;

/**
 * Read-only view for configuration access.
 *
 * <p>This interface exists to reduce implicit dependencies on {@link ProductionConfig#getInstance()} and
 * to make configuration semantics (priority/origin) explicit.</p>
 */
public interface ConfigView {
    String getString(String key, String defaultValue);

    int getInt(String key, int defaultValue);

    long getLong(String key, long defaultValue);

    double getDouble(String key, double defaultValue);

    boolean getBoolean(String key, boolean defaultValue);

    ConfigOrigin getOrigin(String key);
}
