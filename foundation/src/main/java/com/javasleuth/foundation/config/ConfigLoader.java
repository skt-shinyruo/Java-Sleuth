package com.javasleuth.foundation.config;

import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class ConfigLoader {
    public enum ForbiddenKeyPolicy {
        OFF,
        WARN,
        STRICT;

        static ForbiddenKeyPolicy fromSystemProperty() {
            String raw = System.getProperty("sleuth.config.forbidden.keys.policy");
            if (raw == null || raw.trim().isEmpty()) {
                return STRICT;
            }
            String v = raw.trim().toLowerCase();
            if ("warn".equals(v)) {
                return WARN;
            }
            if ("strict".equals(v)) {
                return STRICT;
            }
            return OFF;
        }
    }

    public static final String DEFAULT_CONFIG_RESOURCE = "/sleuth-default.properties";
    public static final String DEFAULT_CONFIG_FILE_NAME = "sleuth.properties";
    public static final String CONFIG_FILE_PROPERTY = "sleuth.config.file";

    private static final Set<String> FORBIDDEN_KEYS = new HashSet<>(SleuthConfigSchema.forbiddenKeys());

    public ConfigLoadResult load() {
        Properties defaults = new Properties();
        try (InputStream defaultStream = getClass().getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
            if (defaultStream != null) {
                defaults.load(defaultStream);
            }
        } catch (IOException e) {
            System.err.println("SLEUTH: Warning: Failed to load default configuration: " + e.getMessage());
        }
        if (defaults.isEmpty()) {
            DefaultConfigFallback.applyFallbackDefaults(defaults);
        }

        String explicitConfigPath = System.getProperty(CONFIG_FILE_PROPERTY);
        File configFile = explicitConfigPath != null && !explicitConfigPath.trim().isEmpty()
            ? new File(explicitConfigPath)
            : new File(DEFAULT_CONFIG_FILE_NAME);

        Properties fileProps = new Properties();
        boolean loadedFromFile = false;
        if (configFile.exists()) {
            try (FileInputStream fileStream = new FileInputStream(configFile)) {
                fileProps.load(fileStream);
                loadedFromFile = true;
                System.err.println("SLEUTH: Loaded configuration from: " + configFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("SLEUTH: Warning: Failed to load configuration file: " + e.getMessage());
            }
        }

        Properties effective = new Properties();
        effective.putAll(defaults);
        effective.putAll(fileProps);

        // Load system properties overrides for known keys (still checked dynamically in ConfigView).
        for (String key : effective.stringPropertyNames()) {
            String sysProp = System.getProperty("sleuth." + key);
            if (sysProp != null) {
                effective.setProperty(key, sysProp);
            }
        }

        validateForbiddenKeys(fileProps, ForbiddenKeyPolicy.fromSystemProperty());

        return new ConfigLoadResult(defaults, fileProps, effective, configFile, loadedFromFile);
    }

    private static void validateForbiddenKeys(Properties fileProps, ForbiddenKeyPolicy policy) {
        if (policy == null || policy == ForbiddenKeyPolicy.OFF) {
            return;
        }
        for (String key : FORBIDDEN_KEYS) {
            boolean inFile = fileProps != null && fileProps.getProperty(key) != null;
            boolean inSys = System.getProperty("sleuth." + key) != null;
            if (!inFile && !inSys) {
                continue;
            }
            String msg = forbiddenKeyMessage(key);
            if (policy == ForbiddenKeyPolicy.STRICT) {
                throw new IllegalArgumentException(msg);
            }
            System.err.println("SLEUTH: Warning: " + msg);
        }
    }

    private static Set<String> forbiddenKeys() {
        return FORBIDDEN_KEYS;
    }

    static String forbiddenKeyMessage(String key) {
        if (key == null) {
            return "Unsupported config key: <null>";
        }
        String lower = key.trim().toLowerCase(Locale.ROOT);
        if ("security.mode".equals(lower) || lower.startsWith("security.hmac.") || lower.startsWith("security.bootstrap.hmac.")) {
            return "Unsupported config key: " + key + " (HMAC mode removed; server is loopback-only)";
        }
        if (lower.startsWith("protocol.")) {
            return "Unsupported config key: " + key + " (legacy protocol removed)";
        }
        return "Unsupported config key: " + key;
    }
}
