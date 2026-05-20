package com.javasleuth.foundation.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Immutable effective snapshot for request-level configuration consistency.
 *
 * <p>Layer priority matches {@link ProductionConfig}: runtime overrides, then system properties captured
 * at config load/reload time, then file values, then defaults. This class never reads live global
 * {@link System} properties; callers must pass any captured system layer explicitly.</p>
 */
public final class ConfigSnapshot implements ConfigView {
    private final Properties baseProperties;
    private final Properties defaultProperties;
    private final Properties fileProperties;
    private final Map<String, String> runtimeOverrides;
    private final Map<String, String> systemOverrides;

    public ConfigSnapshot(
        Properties baseProperties,
        Properties defaultProperties,
        Properties fileProperties,
        Map<String, String> runtimeOverrides,
        Map<String, String> systemOverrides
    ) {
        this.baseProperties = baseProperties != null ? cloneProperties(baseProperties) : new Properties();
        this.defaultProperties = defaultProperties != null ? cloneProperties(defaultProperties) : new Properties();
        this.fileProperties = fileProperties != null ? cloneProperties(fileProperties) : new Properties();
        this.runtimeOverrides = runtimeOverrides != null ? new HashMap<>(runtimeOverrides) : new HashMap<>();
        this.systemOverrides = systemOverrides != null ? new HashMap<>(systemOverrides) : new HashMap<>();
    }

    @Override
    public String getString(String key, String defaultValue) {
        if (key == null) {
            return defaultValue;
        }
        String runtime = runtimeOverrides.get(key);
        if (runtime != null) {
            return runtime;
        }
        String sys = systemOverrides.get(key);
        if (sys != null) {
            return sys;
        }
        return baseProperties.getProperty(key, defaultValue);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public long getLong(String key, long defaultValue) {
        try {
            return Long.parseLong(getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(getString(key, String.valueOf(defaultValue)));
    }

    @Override
    public ConfigOrigin getOrigin(String key) {
        if (key == null) {
            return ConfigOrigin.UNKNOWN;
        }
        if (runtimeOverrides.containsKey(key)) {
            return ConfigOrigin.RUNTIME_OVERRIDE;
        }
        if (systemOverrides.containsKey(key)) {
            return ConfigOrigin.SYSTEM_PROPERTY;
        }
        if (fileProperties.getProperty(key) != null) {
            return ConfigOrigin.FILE;
        }
        if (defaultProperties.getProperty(key) != null) {
            return ConfigOrigin.DEFAULT;
        }
        return ConfigOrigin.UNKNOWN;
    }

    private static Properties cloneProperties(Properties src) {
        Properties out = new Properties();
        out.putAll(src);
        return out;
    }
}
