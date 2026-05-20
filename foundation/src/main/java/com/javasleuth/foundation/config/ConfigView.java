package com.javasleuth.foundation.config;

/**
 * Read-only view for configuration access.
 *
 * <p>This interface exists to reduce implicit dependencies on global configuration state and to make
 * configuration semantics (priority/origin) explicit. Implementations should not read mutable global
 * System properties on every access; external sources should be captured into the view first.</p>
 */
public interface ConfigView {
    String getString(String key, String defaultValue);

    int getInt(String key, int defaultValue);

    long getLong(String key, long defaultValue);

    double getDouble(String key, double defaultValue);

    boolean getBoolean(String key, boolean defaultValue);

    ConfigOrigin getOrigin(String key);
}
