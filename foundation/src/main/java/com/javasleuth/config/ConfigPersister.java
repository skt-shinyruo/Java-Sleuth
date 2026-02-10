package com.javasleuth.config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public final class ConfigPersister {
    public void save(File configFile, Properties baseProperties, Map<String, String> runtimeOverrides, boolean includeRuntimeOverrides)
        throws IOException {
        File out = configFile != null ? configFile : new File(ConfigLoader.DEFAULT_CONFIG_FILE_NAME);
        Properties toSave = new Properties();
        if (baseProperties != null) {
            toSave.putAll(baseProperties);
        }
        if (includeRuntimeOverrides && runtimeOverrides != null && !runtimeOverrides.isEmpty()) {
            for (Map.Entry<String, String> e : runtimeOverrides.entrySet()) {
                if (e == null || e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                toSave.setProperty(e.getKey(), e.getValue());
            }
        }
        try (FileOutputStream output = new FileOutputStream(out)) {
            String comment = includeRuntimeOverrides
                ? "Java-Sleuth Production Configuration (including runtime overrides)"
                : "Java-Sleuth Production Configuration";
            toSave.store(output, comment);
        }
    }
}

