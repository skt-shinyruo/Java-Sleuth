package com.javasleuth.config;

import java.io.File;
import java.util.Properties;

public final class ConfigLoadResult {
    private final Properties defaultProperties;
    private final Properties fileProperties;
    private final Properties effectiveProperties;
    private final File configFile;
    private final boolean loadedFromFile;

    public ConfigLoadResult(
        Properties defaultProperties,
        Properties fileProperties,
        Properties effectiveProperties,
        File configFile,
        boolean loadedFromFile
    ) {
        this.defaultProperties = defaultProperties != null ? defaultProperties : new Properties();
        this.fileProperties = fileProperties != null ? fileProperties : new Properties();
        this.effectiveProperties = effectiveProperties != null ? effectiveProperties : new Properties();
        this.configFile = configFile;
        this.loadedFromFile = loadedFromFile;
    }

    public Properties getDefaultProperties() {
        return defaultProperties;
    }

    public Properties getFileProperties() {
        return fileProperties;
    }

    public Properties getEffectiveProperties() {
        return effectiveProperties;
    }

    public File getConfigFile() {
        return configFile;
    }

    public boolean isLoadedFromFile() {
        return loadedFromFile;
    }
}

