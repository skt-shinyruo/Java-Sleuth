package com.javasleuth.config;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProductionConfig implements ConfigView, MutableConfig {
    private static final String SYS_PROP_PREFIX = "sleuth.";

    private final Properties properties;
    private final Properties defaultProperties;
    private final Properties fileProperties;
    private final File configFile;

    private final SensitiveKeyMasker masker;
    private final RuntimeConfigStore runtimeStore;
    private final LogPathResolver logPathResolver;
    private final ConfigLoader loader;
    private final ConfigPersister persister;

    private volatile boolean configLoaded = false;

    private static ProductionConfig instance;
    private static final AtomicBoolean LOADING = new AtomicBoolean(false);

    private ProductionConfig() {
        this.masker = new SensitiveKeyMasker();
        this.runtimeStore = new RuntimeConfigStore(masker);
        this.logPathResolver = new LogPathResolver();
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
        LOADING.set(true);
        try {
            ConfigLoadResult loaded = loader.load();
            configLoaded = true;
            return loaded;
        } finally {
            LOADING.set(false);
        }
    }

    // Server configuration
    public String getServerBindAddress() {
        return getString("server.bind.address", "127.0.0.1");
    }

    public int getServerPort() {
        return getInt("server.port", 3658);
    }

    public int getMaxConnections() {
        return getInt("server.max.connections", 10);
    }

    public int getServerExecutorQueueCapacity() {
        int v = getInt("server.executor.queue.capacity", 50);
        if (v <= 0) {
            v = 50;
        }
        return v;
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

    public int getCommandExecutorCoreSize() {
        int v = getInt("performance.command.executor.core", getThreadPoolCoreSize());
        if (v <= 0) {
            v = getThreadPoolCoreSize();
        }
        return v;
    }

    public int getCommandExecutorMaxSize() {
        int v = getInt("performance.command.executor.max", getThreadPoolMaxSize());
        if (v <= 0) {
            v = getThreadPoolMaxSize();
        }
        return v;
    }

    public int getCommandExecutorQueueCapacity() {
        int v = getInt("performance.command.executor.queue.capacity", 200);
        if (v <= 0) {
            v = 200;
        }
        return v;
    }

    public long getCommandTimeout() {
        long v = getLong("performance.command.timeout", 60000);
        long cap = getLong("performance.command.timeout.max", 300000);
        if (cap > 0 && v > cap) {
            v = cap;
        }
        return v;
    }

    public long getMaxCommandTimeout() {
        return getLong("performance.command.timeout.max", 300000);
    }

    public int getJobsMax() {
        return getInt("jobs.max", 200);
    }

    public long getJobsTtlMs() {
        return getLong("jobs.ttl.ms", 3600000);
    }

    public int getJobsOutputMaxBytes() {
        return getInt("jobs.output.max.bytes", 262144);
    }

    public int getJobsMaxRunning() {
        int v = getInt("jobs.max.running", 4);
        if (v <= 0) {
            v = 1;
        }
        return Math.min(v, 64);
    }

    public int getJobsQueueCapacity() {
        int v = getInt("jobs.queue.capacity", 20);
        if (v <= 0) {
            v = 1;
        }
        return Math.min(v, 10000);
    }

    // Security configuration
    public boolean isInputValidationEnabled() {
        return getBoolean("security.input.validation", true);
    }

    public boolean isAuditLoggingEnabled() {
        return getBoolean("security.audit.logging", true);
    }

    public boolean isAuthorizationEnabled() {
        return getBoolean("security.authorization.enabled", true);
    }

    public boolean isAnonymousViewerEnabled() {
        return getBoolean("security.anonymous.viewer", true);
    }

    public int getMaxCommandLength() {
        return getInt("security.max.command.length", 1000);
    }

    public String getAllowedCommands() {
        return getString("security.allowed.commands", "*");
    }

    public String getSecurityHmacSecret() {
        return getString("security.hmac.secret", "");
    }

    public boolean isHmacSecretAutogenOnLoopbackEnabled() {
        return getBoolean("security.hmac.secret.autogen.on.loopback", true);
    }

    public boolean isHmacSecretAutogenPrintEnabled() {
        return getBoolean("security.hmac.secret.autogen.print", true);
    }

    public long getSecurityHmacTimestampWindowMs() {
        return getLong("security.hmac.timestamp.window.ms", 30000);
    }

    public int getSecurityHmacNonceCacheSize() {
        return getInt("security.hmac.nonce.cache.size", 10000);
    }

    public boolean isHighImpactConfirmEnabled() {
        return getBoolean("security.impact.high.confirm.enabled", true);
    }

    public int getHighImpactConcurrentLimit() {
        int v = getInt("security.impact.high.concurrent.limit", 1);
        if (v <= 0) {
            v = 0;
        }
        return v;
    }

    public boolean isHmacBootstrapOnAttachEnabled() {
        return getBoolean("security.bootstrap.hmac.on.attach", true);
    }

    public int getHmacBootstrapSecretBytes() {
        return getInt("security.bootstrap.hmac.secret.bytes", 32);
    }

    public String getHmacSessionRole() {
        return getString("security.hmac.session.role", "operator");
    }

    public boolean isPasswordAuthEnabled() {
        return getBoolean("security.auth.password.enabled", false);
    }

    public String getAuthAdminPassword() {
        return getString("security.auth.admin.password", "");
    }

    public String getAuthOperatorPassword() {
        return getString("security.auth.operator.password", "");
    }

    public String getAuthViewerPassword() {
        return getString("security.auth.viewer.password", "");
    }

    // Protocol configuration
        public String getProtocolMode() {
        String mode = getString("protocol.mode", "framed");
        if (mode == null) {
            throw new IllegalArgumentException("protocol.mode is required");
        }
        String v = mode.trim().toLowerCase();
        if (!"framed".equals(v) && !"binary".equals(v)) {
            throw new IllegalArgumentException("Unsupported protocol.mode: " + mode + " (allowed: framed|binary)");
        }
        return v;
    }


    public boolean isFramedProtocolEnabled() {
        return "framed".equalsIgnoreCase(getProtocolMode());
    }

    public boolean isBinaryProtocolEnabled() {
        return "binary".equalsIgnoreCase(getProtocolMode());
    }

    public boolean isStreamingEnabled() {
        return getBoolean("protocol.streaming.enabled", true);
    }

    public int getFrameMaxPayload() {
        return getInt("protocol.frame.max.payload", 4096);
    }


    // Security mode configuration
    public String getSecurityMode() {
        return getString("security.mode", "off");
    }

    // Plugin configuration
    public boolean isPluginsEnabled() {
        return getBoolean("plugins.enabled", false);
    }

    public boolean isPluginsServiceLoaderEnabled() {
        return getBoolean("plugins.serviceloader.enabled", false);
    }

    public String getPluginsAllowlistSha256() {
        return getString("plugins.allowlist.sha256", "");
    }

    public String getPluginDirectory() {
        return getString("plugins.directory", "plugins");
    }

    public String getPluginConflictStrategy() {
        return getString("plugins.conflict.strategy", "prefer-builtin");
    }

    // Monitoring queue configuration
    public int getWatchQueueCapacity() {
        return getInt("monitoring.watch.queue.capacity", 1000);
    }

    public boolean isWatchDropOnFull() {
        return getBoolean("monitoring.watch.drop.on.full", true);
    }

    public int getTraceQueueCapacity() {
        return getInt("monitoring.trace.queue.capacity", 2000);
    }

    public boolean isTraceDropOnFull() {
        return getBoolean("monitoring.trace.drop.on.full", true);
    }

    public double getTraceSampleRate() {
        return getDouble("monitoring.trace.sample.rate", 0.1);
    }

    public double getMonitorSampleRate() {
        return getDouble("monitoring.monitor.sample.rate", 1.0);
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

    public boolean isAuditConsoleEnabled() {
        return getBoolean("logging.audit.console.enabled", false);
    }

    public String getAuditLogFilePath() {
        String configured = getString("logging.audit.file.path", "");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return logPathResolver.defaultLogPath("sleuth-audit.log");
    }

    public String getSecurityLogFilePath() {
        String configured = getString("logging.security.file.path", "");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return logPathResolver.defaultLogPath("sleuth-security.log");
    }

    public boolean isPerformanceLogEnabled() {
        return getBoolean("logging.performance.enabled", false);
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

    // Configuration status
    public boolean isConfigLoaded() {
        return configLoaded;
    }

    public static boolean isLoading() {
        return LOADING.get();
    }

    public String getConfigStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== CONFIGURATION STATUS ===\n");
        status.append("Config Loaded: ").append(configLoaded ? "YES" : "NO").append("\n");
        status.append("Properties Count: ").append(properties.size()).append("\n");
        status.append("Config File: ").append(configFile != null ? configFile.getAbsolutePath() : "sleuth.properties").append("\n");
        status.append("Runtime Overrides: ").append(runtimeStore.size()).append("\n");

        status.append("\n-- Key Settings --\n");
        status.append("Server Port: ").append(getServerPort()).append("\n");
        status.append("Max Connections: ").append(getMaxConnections()).append("\n");
        status.append("Cache TTL: ").append(getCacheTTL()).append("ms\n");
        status.append("Input Validation: ").append(isInputValidationEnabled() ? "ENABLED" : "DISABLED").append("\n");
        status.append("Audit Logging: ").append(isAuditLoggingEnabled() ? "ENABLED" : "DISABLED").append("\n");
        status.append("Metrics: ").append(isMetricsEnabled() ? "ENABLED" : "DISABLED").append("\n");

        // Recent runtime changes (masked summary only).
        int recentLimit = 5;
        if (runtimeStore.size() > 0) {
            status.append("\n-- Recent Runtime Changes (masked) --\n");
            for (ConfigChange c : runtimeStore.getRecentChanges(recentLimit)) {
                if (c == null) {
                    continue;
                }
                status.append("#").append(c.getSequence())
                    .append(" key=").append(c.getKey())
                    .append(" old=").append(c.getOldValueSummary())
                    .append(" new=").append(c.getNewValueSummary())
                    .append(" source=").append(c.getSource())
                    .append(" ts=").append(c.getTimestampMs())
                    .append("\n");
            }
        }

        return status.toString();
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
        if ("protocol.handshake.enabled".equals(key)) {
            throw new IllegalArgumentException("Unsupported config key: protocol.handshake.enabled (handshake is mandatory)");
        }
        if ("protocol.text.end.marker.enabled".equals(key)) {
            throw new IllegalArgumentException("Unsupported config key: protocol.text.end.marker.enabled (legacy protocol removed)");
        }
    }
}
