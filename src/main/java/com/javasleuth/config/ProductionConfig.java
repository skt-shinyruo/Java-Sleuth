package com.javasleuth.config;

import java.io.*;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class ProductionConfig {
    private static final String CONFIG_FILE = "sleuth.properties";
    private static final String DEFAULT_CONFIG = "/sleuth-default.properties";

    private final Properties properties;
    private final ConcurrentHashMap<String, String> runtimeConfig;
    private volatile boolean configLoaded = false;

    private static ProductionConfig instance;

    private ProductionConfig() {
        this.properties = new Properties();
        this.runtimeConfig = new ConcurrentHashMap<>();
        loadConfiguration();
    }

    public static synchronized ProductionConfig getInstance() {
        if (instance == null) {
            instance = new ProductionConfig();
        }
        return instance;
    }

    private void loadConfiguration() {
        try {
            // First load default configuration from resources
            InputStream defaultStream = getClass().getResourceAsStream(DEFAULT_CONFIG);
            if (defaultStream != null) {
                properties.load(defaultStream);
                defaultStream.close();
            }

            // Then load external configuration file
            File configFile = new File(CONFIG_FILE);
            if (configFile.exists()) {
                try (FileInputStream fileStream = new FileInputStream(configFile)) {
                    properties.load(fileStream);
                    System.out.println("Loaded configuration from: " + configFile.getAbsolutePath());
                }
            }

            // Load system properties overrides
            for (String key : properties.stringPropertyNames()) {
                String sysProp = System.getProperty("sleuth." + key);
                if (sysProp != null) {
                    properties.setProperty(key, sysProp);
                }
            }

            configLoaded = true;
        } catch (IOException e) {
            System.err.println("Warning: Failed to load configuration: " + e.getMessage());
            // Use defaults
            setDefaults();
        }
    }

    private void setDefaults() {
        // Server configuration
        properties.setProperty("server.port", "3658");
        properties.setProperty("server.max.connections", "10");
        properties.setProperty("server.connection.timeout", "30000");
        properties.setProperty("server.socket.timeout", "1000");

        // Performance configuration
        properties.setProperty("performance.cache.ttl", "5000");
        properties.setProperty("performance.thread.pool.core", "4");
        properties.setProperty("performance.thread.pool.max", "16");
        properties.setProperty("performance.command.timeout", "60000");

        // Security configuration
        properties.setProperty("security.input.validation", "true");
        properties.setProperty("security.audit.logging", "true");
        properties.setProperty("security.max.command.length", "1000");
        properties.setProperty("security.allowed.commands", "*");

        // Monitoring configuration
        properties.setProperty("monitoring.metrics.enabled", "true");
        properties.setProperty("monitoring.health.checks", "true");
        properties.setProperty("monitoring.cache.cleanup.interval", "300000");
        properties.setProperty("monitoring.jmx.enabled", "true");

        // Logging configuration
        properties.setProperty("logging.level", "INFO");
        properties.setProperty("logging.audit.enabled", "true");
        properties.setProperty("logging.performance.enabled", "true");

        configLoaded = true;
    }

    // Server configuration
    public int getServerPort() {
        return getInt("server.port", 3658);
    }

    public int getMaxConnections() {
        return getInt("server.max.connections", 10);
    }

    public int getConnectionTimeout() {
        return getInt("server.connection.timeout", 30000);
    }

    public int getSocketTimeout() {
        return getInt("server.socket.timeout", 1000);
    }

    // Performance configuration
    public long getCacheTTL() {
        return getLong("performance.cache.ttl", 5000);
    }

    public int getThreadPoolCoreSize() {
        return getInt("performance.thread.pool.core", 4);
    }

    public int getThreadPoolMaxSize() {
        return getInt("performance.thread.pool.max", 16);
    }

    public long getCommandTimeout() {
        return getLong("performance.command.timeout", 60000);
    }

    // Security configuration
    public boolean isInputValidationEnabled() {
        return getBoolean("security.input.validation", true);
    }

    public boolean isAuditLoggingEnabled() {
        return getBoolean("security.audit.logging", true);
    }

    public int getMaxCommandLength() {
        return getInt("security.max.command.length", 1000);
    }

    public String getAllowedCommands() {
        return getString("security.allowed.commands", "*");
    }

    // Monitoring configuration
    public boolean isMetricsEnabled() {
        return getBoolean("monitoring.metrics.enabled", true);
    }

    public boolean areHealthChecksEnabled() {
        return getBoolean("monitoring.health.checks", true);
    }

    public long getCacheCleanupInterval() {
        return getLong("monitoring.cache.cleanup.interval", 300000);
    }

    public boolean isJmxEnabled() {
        return getBoolean("monitoring.jmx.enabled", true);
    }

    // Logging configuration
    public String getLoggingLevel() {
        return getString("logging.level", "INFO");
    }

    public boolean isAuditLogEnabled() {
        return getBoolean("logging.audit.enabled", true);
    }

    public boolean isPerformanceLogEnabled() {
        return getBoolean("logging.performance.enabled", true);
    }

    // Generic getters with runtime override support
    public String getString(String key, String defaultValue) {
        String runtimeValue = runtimeConfig.get(key);
        if (runtimeValue != null) {
            return runtimeValue;
        }
        return properties.getProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        try {
            return Long.parseLong(getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    // Runtime configuration updates
    public void setRuntimeConfig(String key, String value) {
        runtimeConfig.put(key, value);
        System.out.println("Runtime config updated: " + key + " = " + value);
    }

    public void removeRuntimeConfig(String key) {
        runtimeConfig.remove(key);
    }

    public void clearRuntimeConfig() {
        runtimeConfig.clear();
    }

    // Configuration status
    public boolean isConfigLoaded() {
        return configLoaded;
    }

    public String getConfigStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== CONFIGURATION STATUS ===\n");
        status.append("Config Loaded: ").append(configLoaded ? "YES" : "NO").append("\n");
        status.append("Properties Count: ").append(properties.size()).append("\n");
        status.append("Runtime Overrides: ").append(runtimeConfig.size()).append("\n");

        status.append("\n-- Key Settings --\n");
        status.append("Server Port: ").append(getServerPort()).append("\n");
        status.append("Max Connections: ").append(getMaxConnections()).append("\n");
        status.append("Cache TTL: ").append(getCacheTTL()).append("ms\n");
        status.append("Input Validation: ").append(isInputValidationEnabled() ? "ENABLED" : "DISABLED").append("\n");
        status.append("Audit Logging: ").append(isAuditLoggingEnabled() ? "ENABLED" : "DISABLED").append("\n");
        status.append("Metrics: ").append(isMetricsEnabled() ? "ENABLED" : "DISABLED").append("\n");

        return status.toString();
    }

    // Save current configuration
    public void saveConfiguration() throws IOException {
        File configFile = new File(CONFIG_FILE);
        try (FileOutputStream output = new FileOutputStream(configFile)) {
            properties.store(output, "Java-Sleuth Production Configuration");
            System.out.println("Configuration saved to: " + configFile.getAbsolutePath());
        }
    }
}