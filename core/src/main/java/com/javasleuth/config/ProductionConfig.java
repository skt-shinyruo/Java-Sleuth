package com.javasleuth.config;

import com.javasleuth.util.SleuthLogger;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

public class ProductionConfig {
    private static final String CONFIG_FILE = "sleuth.properties";
    private static final String DEFAULT_CONFIG = "/sleuth-default.properties";
    private static final String CONFIG_FILE_PROPERTY = "sleuth.config.file";

    private final Properties properties;
    private final ConcurrentHashMap<String, String> runtimeConfig;
    private volatile boolean configLoaded = false;

    private static ProductionConfig instance;
    private static final AtomicBoolean LOADING = new AtomicBoolean(false);

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
        LOADING.set(true);
        try {
            try {
                // First load default configuration from resources
                InputStream defaultStream = getClass().getResourceAsStream(DEFAULT_CONFIG);
                if (defaultStream != null) {
                    properties.load(defaultStream);
                    defaultStream.close();
                }

                // Then load external configuration file (explicit path > default filename)
                String explicitConfigPath = System.getProperty(CONFIG_FILE_PROPERTY);
                File configFile = explicitConfigPath != null && !explicitConfigPath.trim().isEmpty()
                    ? new File(explicitConfigPath)
                    : new File(CONFIG_FILE);
                if (configFile.exists()) {
                    try (FileInputStream fileStream = new FileInputStream(configFile)) {
                        properties.load(fileStream);
                        SleuthLogger.info("Loaded configuration from: " + configFile.getAbsolutePath());
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
                SleuthLogger.warn("Warning: Failed to load configuration: " + e.getMessage(), e);
                // Use defaults
                setDefaults();
            }
        } finally {
            LOADING.set(false);
        }
    }

    private void setDefaults() {
        // Server configuration
        properties.setProperty("server.bind.address", "127.0.0.1");
        properties.setProperty("server.port", "3658");
        properties.setProperty("server.max.connections", "10");
        // Client accept/handling executor queue (backpressure + memory bound)
        properties.setProperty("server.executor.queue.capacity", "50");
        properties.setProperty("server.connection.timeout", "30000");
        properties.setProperty("server.socket.timeout", "1000");

        // Performance configuration
        properties.setProperty("performance.cache.ttl", "5000");
        properties.setProperty("performance.thread.pool.core", "4");
        properties.setProperty("performance.thread.pool.max", "16");
        // Dedicated command execution executor (avoid unbounded newCachedThreadPool)
        properties.setProperty("performance.command.executor.core", properties.getProperty("performance.thread.pool.core", "4"));
        properties.setProperty("performance.command.executor.max", properties.getProperty("performance.thread.pool.max", "16"));
        properties.setProperty("performance.command.executor.queue.capacity", "200");
        properties.setProperty("performance.command.timeout", "60000");
        properties.setProperty("performance.command.timeout.max", "300000");
        properties.setProperty("performance.maintenance.force_gc", "false");

        // Enhancement failure strategy (cooldown + retry, avoid silent disable)
        properties.setProperty("enhancement.failure.cooldown.ms", "30000");
        properties.setProperty("enhancement.failure.log.interval.ms", "60000");

        // Job retention configuration
        properties.setProperty("jobs.max", "200");
        properties.setProperty("jobs.ttl.ms", "3600000");
        properties.setProperty("jobs.output.max.bytes", "262144");
        // Background job execution limits (backpressure + stability)
        properties.setProperty("jobs.max.running", "4");
        properties.setProperty("jobs.queue.capacity", "20");

        // Security configuration
        properties.setProperty("security.input.validation", "true");
        properties.setProperty("security.audit.logging", "true");
        properties.setProperty("security.max.command.length", "1000");
        properties.setProperty("security.allowed.commands", "*");
        properties.setProperty("security.authorization.enabled", "true");
        properties.setProperty("security.anonymous.viewer", "false");
        properties.setProperty("security.mode", "hmac");
        properties.setProperty("security.hmac.secret", "");
        // Loopback self-contained startup: auto-generate temporary secret if empty.
        properties.setProperty("security.hmac.secret.autogen.on.loopback", "true");
        properties.setProperty("security.hmac.secret.autogen.print", "true");
        properties.setProperty("security.hmac.timestamp.window.ms", "30000");
        properties.setProperty("security.hmac.nonce.cache.size", "10000");
        properties.setProperty("security.dangerous.confirm.enabled", "true");
        properties.setProperty("security.dangerous.confirm.ttl.ms", "60000");
        properties.setProperty("security.dangerous.confirm.token.bytes", "12");
        properties.setProperty("security.dangerous.confirm.cache.size", "2000");
        // High impact commands governance (non-privileged but performance-risky operations)
        properties.setProperty("security.impact.high.confirm.enabled", "true");
        properties.setProperty("security.impact.high.concurrent.limit", "1");
        properties.setProperty("security.bootstrap.hmac.on.attach", "true");
        properties.setProperty("security.bootstrap.hmac.secret.bytes", "32");
        properties.setProperty("security.hmac.session.role", "operator");
        properties.setProperty("security.auth.password.enabled", "false");
        properties.setProperty("security.auth.admin.password", "");
        properties.setProperty("security.auth.operator.password", "");
        properties.setProperty("security.auth.viewer.password", "");

        // Protocol configuration
        properties.setProperty("protocol.mode", "framed");
        properties.setProperty("protocol.streaming.enabled", "true");
        properties.setProperty("protocol.frame.max.payload", "4096");
        properties.setProperty("protocol.handshake.enabled", "true");
        properties.setProperty("protocol.text.max.line.bytes", "8192");
        properties.setProperty("protocol.text.end.marker.enabled", "true");

        // Plugin configuration
        properties.setProperty("plugins.enabled", "false");
        // When disabled, do not load CommandProvider from the target application's classpath by default.
        properties.setProperty("plugins.serviceloader.enabled", "false");
        properties.setProperty("plugins.allowlist.sha256", "");
        properties.setProperty("plugins.directory", "plugins");
        properties.setProperty("plugins.conflict.strategy", "prefer-builtin");

        // Monitoring queue configuration
        properties.setProperty("monitoring.watch.queue.capacity", "1000");
        properties.setProperty("monitoring.watch.drop.on.full", "true");
        properties.setProperty("monitoring.trace.queue.capacity", "2000");
        properties.setProperty("monitoring.trace.drop.on.full", "true");
        properties.setProperty("monitoring.trace.sample.rate", "0.1");
        properties.setProperty("monitoring.monitor.sample.rate", "1.0");

        // VmTool (instance tracking) configuration
        properties.setProperty("vmtool.track.max.entries", "500");
        properties.setProperty("vmtool.track.class.limit", "50");

        // Monitoring configuration
        properties.setProperty("monitoring.metrics.enabled", "true");
        properties.setProperty("monitoring.health.checks", "true");
        properties.setProperty("monitoring.cache.cleanup.interval", "300000");
        properties.setProperty("monitoring.jmx.enabled", "true");

        // Logging configuration
        properties.setProperty("logging.level", "INFO");
        // Console logging (stderr) is useful for local troubleshooting; tests may override via -Dsleuth.logging.level=ERROR.
        properties.setProperty("logging.console.enabled", "true");
        properties.setProperty("logging.audit.enabled", "true");
        properties.setProperty("logging.audit.console.enabled", "false");
        properties.setProperty("logging.audit.file.path", "");
        properties.setProperty("logging.security.file.path", "");
        // Performance/health logging to stdout/stderr is noisy in production; keep it opt-in.
        properties.setProperty("logging.performance.enabled", "false");

        configLoaded = true;
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
        return getString("protocol.mode", "framed");
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

    public boolean isHandshakeEnabled() {
        return getBoolean("protocol.handshake.enabled", true);
    }

    public boolean isTextEndMarkerEnabled() {
        return getBoolean("protocol.text.end.marker.enabled", true);
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
        return defaultLogPath("sleuth-audit.log");
    }

    public String getSecurityLogFilePath() {
        String configured = getString("logging.security.file.path", "");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return defaultLogPath("sleuth-security.log");
    }

    public boolean isPerformanceLogEnabled() {
        return getBoolean("logging.performance.enabled", false);
    }

    // Generic getters with runtime override support
    public String getString(String key, String defaultValue) {
        String runtimeValue = runtimeConfig.get(key);
        if (runtimeValue != null) {
            return runtimeValue;
        }
        String sysProp = System.getProperty("sleuth." + key);
        if (sysProp != null) {
            return sysProp;
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

    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(getString(key, String.valueOf(defaultValue)));
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

    public static boolean isLoading() {
        return LOADING.get();
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
        saveConfiguration(false);
    }

    public void saveConfiguration(boolean includeRuntimeOverrides) throws IOException {
        File configFile = new File(CONFIG_FILE);
        Properties toSave = new Properties();
        toSave.putAll(properties);
        if (includeRuntimeOverrides && !runtimeConfig.isEmpty()) {
            for (Map.Entry<String, String> e : runtimeConfig.entrySet()) {
                if (e == null || e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                toSave.setProperty(e.getKey(), e.getValue());
            }
        }
        try (FileOutputStream output = new FileOutputStream(configFile)) {
            String comment = includeRuntimeOverrides
                ? "Java-Sleuth Production Configuration (including runtime overrides)"
                : "Java-Sleuth Production Configuration";
            toSave.store(output, comment);
        }
    }

    private String maskIfSensitive(String key, String value) {
        if (key == null) {
            return value;
        }
        String k = key.toLowerCase();
        if (k.contains("password") || k.contains("secret") || k.contains("token") || k.contains("credential") ||
            k.contains("session") || k.contains("apikey") || k.contains("api_key")) {
            if (value == null) {
                return "null";
            }
            if (value.length() <= 4) {
                return "***";
            }
            return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
        }
        return value;
    }

    private static String defaultLogPath(String baseName) {
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp == null || tmp.trim().isEmpty()) {
            tmp = ".";
        }
        String name = (baseName == null || baseName.trim().isEmpty()) ? "sleuth.log" : baseName.trim();
        String pid = currentPid();
        String fileName = appendPidSuffix(name, pid);
        return new File(tmp, fileName).getAbsolutePath();
    }

    private static String currentPid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            if (name == null) {
                return "unknown";
            }
            int idx = name.indexOf('@');
            if (idx > 0) {
                return name.substring(0, idx);
            }
            return name;
        } catch (Exception ignore) {
            return "unknown";
        }
    }

    private static String appendPidSuffix(String fileName, String pid) {
        String p = (pid == null || pid.trim().isEmpty()) ? "unknown" : pid.trim();
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            return fileName.substring(0, dot) + "-" + p + fileName.substring(dot);
        }
        return fileName + "-" + p;
    }
}
