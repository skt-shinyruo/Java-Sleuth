package com.javasleuth.foundation.config;

import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.foundation.config.schema.ConfigKey;
import com.javasleuth.foundation.config.schema.ConfigValidationResult;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public class ProductionConfig implements ConfigView, MutableConfig {
    private static final String SYS_PROP_PREFIX = "sleuth.";

    private volatile LoadedConfigState state;

    private final SensitiveKeyMasker masker;
    private final RuntimeConfigStore runtimeStore;
    private final ConfigLoader loader;
    private final ConfigPersister persister;

    private static final class LoadedConfigState {
        private final Properties properties;
        private final Properties defaultProperties;
        private final Properties fileProperties;
        private final File configFile;
        private final boolean loadedFromFile;

        private LoadedConfigState(ConfigLoadResult loaded) {
            Properties defaults = loaded != null ? loaded.getDefaultProperties() : null;
            Properties file = loaded != null ? loaded.getFileProperties() : null;
            Properties effective = loaded != null ? loaded.getEffectiveProperties() : null;

            this.defaultProperties = defaults != null ? defaults : new Properties();
            this.fileProperties = file != null ? file : new Properties();
            this.properties = effective != null ? effective : new Properties();
            this.configFile = loaded != null ? loaded.getConfigFile() : null;
            this.loadedFromFile = loaded != null && loaded.isLoadedFromFile();
        }
    }

    public ProductionConfig() {
        this.masker = new SensitiveKeyMasker();
        this.runtimeStore = new RuntimeConfigStore(masker);
        this.loader = new ConfigLoader();
        this.persister = new ConfigPersister();

        ConfigLoadResult loaded = loadConfiguration();
        applyLoadedConfig(loaded);
    }

    /**
     * Create a new ProductionConfig instance using the default load/persist behavior.
     *
     * <p>Note: This returns a NEW instance every time; configuration is attach-scope and must be owned
     * by the agent runtime lifecycle rather than a global singleton.</p>
     */
    public static ProductionConfig createDefault() {
        return new ProductionConfig();
    }

    /**
     * Reload configuration from defaults + config file (as defined by {@code sleuth.config.file} sysprop),
     * without clearing runtime overrides.
     *
     * <p>This method is synchronized to ensure the loaded config state is swapped atomically.</p>
     *
     * @return true if a config file exists and was loaded; false if defaults were used
     */
    public synchronized boolean reloadConfiguration() {
        ConfigLoadResult loaded = loadConfiguration();
        applyLoadedConfig(loaded);
        return loaded != null && loaded.isLoadedFromFile();
    }

    public File getLoadedConfigFile() {
        LoadedConfigState s = state;
        return s != null ? s.configFile : null;
    }

    public boolean isLoadedFromFile() {
        LoadedConfigState s = state;
        return s != null && s.loadedFromFile;
    }

    private ConfigLoadResult loadConfiguration() {
        return loader.load();
    }

    private void applyLoadedConfig(ConfigLoadResult loaded) {
        this.state = new LoadedConfigState(loaded);
    }

    // Generic getters with runtime override support
    @Override
    public String getString(String key, String defaultValue) {
        LoadedConfigState s = state;
        String runtimeValue = runtimeStore.get(key);
        if (runtimeValue != null) {
            return runtimeValue;
        }
        String sysProp = System.getProperty(SYS_PROP_PREFIX + key);
        if (sysProp != null) {
            return sysProp;
        }
        if (s == null) {
            return defaultValue;
        }
        return s.properties.getProperty(key, defaultValue);
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
        ConfigKey<?> schemaKey = SleuthConfigSchema.byKey(key);
        if (schemaKey == null) {
            throw new IllegalArgumentException("Unknown config key: " + key);
        }
        ConfigValidationResult result = schemaKey.validateRuntimeValue(value);
        if (result == null || !result.isValid()) {
            throw new IllegalArgumentException(result != null ? result.getError() : "Invalid config " + key);
        }
        runtimeStore.set(key, result.getNormalizedValue(), source);
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
        LoadedConfigState s = state;
        if (runtimeStore.get(key) != null) {
            return ConfigOrigin.RUNTIME_OVERRIDE;
        }
        if (System.getProperty(SYS_PROP_PREFIX + key) != null) {
            return ConfigOrigin.SYSTEM_PROPERTY;
        }
        if (s != null && s.fileProperties.getProperty(key) != null) {
            return ConfigOrigin.FILE;
        }
        if (s != null && s.defaultProperties.getProperty(key) != null) {
            return ConfigOrigin.DEFAULT;
        }
        return ConfigOrigin.UNKNOWN;
    }

    // Save current configuration
    public void saveConfiguration() throws IOException {
        saveConfiguration(false);
    }

    public void saveConfiguration(boolean includeRuntimeOverrides) throws IOException {
        LoadedConfigState s = state;
        File out = s != null && s.configFile != null ? s.configFile : new File(ConfigLoader.DEFAULT_CONFIG_FILE_NAME);
        Properties effective = s != null ? s.properties : new Properties();
        Map<String, String> runtime = includeRuntimeOverrides ? runtimeStore.snapshot() : null;
        persister.save(out, effective, runtime, includeRuntimeOverrides);
    }

    public ConfigSnapshot snapshot() {
        LoadedConfigState s = state;
        Properties effective = s != null ? s.properties : new Properties();
        Properties defaults = s != null ? s.defaultProperties : new Properties();
        Properties file = s != null ? s.fileProperties : new Properties();
        return new ConfigSnapshot(effective, defaults, file, runtimeStore.snapshot(), null);
    }

    public <T> T read(ConfigKey<T> key) {
        if (key == null) {
            return null;
        }
        return key.read(this);
    }

    public SleuthConfig typedSnapshot() {
        return SleuthConfigParser.parse(snapshot());
    }

    public String getKnownRaw(ConfigKey<?> key) {
        if (key == null) {
            return null;
        }
        return getString(key.getKey(), key.getLiteralDefaultValue());
    }

    private static void validateRuntimeOverrideKey(String key) {
        if (key == null) {
            return;
        }
        if (SleuthConfigSchema.forbiddenKeys().contains(key)) {
            throw new IllegalArgumentException(ConfigLoader.forbiddenKeyMessage(key));
        }
    }
}
