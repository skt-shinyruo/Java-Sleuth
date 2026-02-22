package com.javasleuth.foundation.config;

import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public class ProductionConfig implements ConfigView, MutableConfig {
    private static final String SYS_PROP_PREFIX = "sleuth.";

    private final Properties properties;
    private final Properties defaultProperties;
    private final Properties fileProperties;
    private final File configFile;

    private final SensitiveKeyMasker masker;
    private final RuntimeConfigStore runtimeStore;
    private final ConfigLoader loader;
    private final ConfigPersister persister;

    private static ProductionConfig instance;

    private ProductionConfig() {
        this.masker = new SensitiveKeyMasker();
        this.runtimeStore = new RuntimeConfigStore(masker);
        this.loader = new ConfigLoader();
        this.persister = new ConfigPersister();

        ConfigLoadResult loaded = loadConfiguration();
        this.defaultProperties = loaded.getDefaultProperties();
        this.fileProperties = loaded.getFileProperties();
        this.properties = loaded.getEffectiveProperties();
        this.configFile = loaded.getConfigFile();
    }

    public static synchronized ProductionConfig getInstance() {
        if (instance == null) {
            instance = new ProductionConfig();
        }
        return instance;
    }

    private ConfigLoadResult loadConfiguration() {
        return loader.load();
    }

    // Generic getters with runtime override support
    @Override
    public String getString(String key, String defaultValue) {
        String runtimeValue = runtimeStore.get(key);
        if (runtimeValue != null) {
            return runtimeValue;
        }
        String sysProp = System.getProperty(SYS_PROP_PREFIX + key);
        if (sysProp != null) {
            return sysProp;
        }
        return properties.getProperty(key, defaultValue);
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
        String value = getString(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    // Runtime configuration updates
    public void setRuntimeConfig(String key, String value) {
        setRuntimeConfig(key, value, ConfigUpdateSource.UNKNOWN);
    }

    public void removeRuntimeConfig(String key) {
        removeRuntimeConfig(key, ConfigUpdateSource.UNKNOWN);
    }

    public void clearRuntimeConfig() {
        clearRuntimeConfig(ConfigUpdateSource.UNKNOWN);
    }

    @Override
    public void setRuntimeConfig(String key, String value, ConfigUpdateSource source) {
        validateRuntimeOverrideKey(key);
        runtimeStore.set(key, value, source);
    }

    @Override
    public void removeRuntimeConfig(String key, ConfigUpdateSource source) {
        validateRuntimeOverrideKey(key);
        runtimeStore.remove(key, source);
    }

    @Override
    public void clearRuntimeConfig(ConfigUpdateSource source) {
        runtimeStore.clear(source);
    }

    @Override
    public ConfigOrigin getOrigin(String key) {
        if (key == null) {
            return ConfigOrigin.UNKNOWN;
        }
        if (runtimeStore.get(key) != null) {
            return ConfigOrigin.RUNTIME_OVERRIDE;
        }
        if (System.getProperty(SYS_PROP_PREFIX + key) != null) {
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

    // Save current configuration
    public void saveConfiguration() throws IOException {
        saveConfiguration(false);
    }

    public void saveConfiguration(boolean includeRuntimeOverrides) throws IOException {
        File out = configFile != null ? configFile : new File(ConfigLoader.DEFAULT_CONFIG_FILE_NAME);
        Map<String, String> runtime = includeRuntimeOverrides ? runtimeStore.snapshot() : null;
        persister.save(out, properties, runtime, includeRuntimeOverrides);
    }

    public ConfigSnapshot snapshot() {
        return new ConfigSnapshot(properties, defaultProperties, fileProperties, runtimeStore.snapshot(), null);
    }

    private static void validateRuntimeOverrideKey(String key) {
        if (key == null) {
            return;
        }
        if (SleuthConfigSchema.forbiddenKeys().contains(key)) {
            throw new IllegalArgumentException("Unsupported config key: " + key + " (legacy protocol removed)");
        }
    }
}
