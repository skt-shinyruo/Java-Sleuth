package com.javasleuth.foundation.config.schema;

import com.javasleuth.foundation.config.SleuthDefaults;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 构建期 Schema/默认产物一致性校验入口（供 Maven 调用）。
 *
 * <p>默认产物包括：</p>
 * <ul>
 *   <li>{@code /sleuth-default.properties}</li>
 *   <li>{@link SleuthDefaults}（资源缺失时 fallback 默认集合）</li>
 * </ul>
 */
public final class SleuthSchemaVerifierMain {
    private SleuthSchemaVerifierMain() {}

    public static void main(String[] args) throws Exception {
        // 1) Schema 自校验
        ConfigSchemaValidator.validateOrThrow();

        // 2) Schema vs default properties
        Properties fromFile = new Properties();
        try (InputStream in = SleuthSchemaVerifierMain.class.getResourceAsStream("/sleuth-default.properties")) {
            if (in == null) {
                throw new IllegalStateException("Missing /sleuth-default.properties on classpath");
            }
            fromFile.load(in);
        }

        Properties fromSchema = new Properties();
        for (ConfigKey<?> k : SleuthConfigSchema.keys()) {
            fromSchema.setProperty(k.getKey(), k.getLiteralDefaultValue());
        }

        // Check: file contains exactly schema keys (SSOT 收敛)
        Set<String> fileKeys = new HashSet<>(fromFile.stringPropertyNames());
        Set<String> schemaKeys = new HashSet<>(fromSchema.stringPropertyNames());
        if (!fileKeys.equals(schemaKeys)) {
            Set<String> missing = new HashSet<>(schemaKeys);
            missing.removeAll(fileKeys);
            Set<String> extra = new HashSet<>(fileKeys);
            extra.removeAll(schemaKeys);

            StringBuilder sb = new StringBuilder();
            sb.append("Default properties keys mismatch with schema. ");
            if (!missing.isEmpty()) {
                sb.append("Missing keys: ").append(missing).append(". ");
            }
            if (!extra.isEmpty()) {
                sb.append("Extra keys: ").append(extra).append(". ");
            }
            throw new IllegalStateException(sb.toString());
        }

        for (String key : schemaKeys) {
            String expected = fromSchema.getProperty(key);
            String actual = fromFile.getProperty(key);
            if (actual == null) {
                throw new IllegalStateException("Missing key in sleuth-default.properties: " + key);
            }
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Default mismatch for key=" + key + ": expected=" + expected + " actual=" + actual);
            }
        }

        // 3) Schema vs SleuthDefaults fallback
        Properties fallback = new Properties();
        SleuthDefaults.apply(fallback);

        Set<String> fallbackKeys = new HashSet<>(fallback.stringPropertyNames());
        if (!fallbackKeys.equals(schemaKeys)) {
            Set<String> missing = new HashSet<>(schemaKeys);
            missing.removeAll(fallbackKeys);
            Set<String> extra = new HashSet<>(fallbackKeys);
            extra.removeAll(schemaKeys);
            StringBuilder sb = new StringBuilder();
            sb.append("SleuthDefaults keys mismatch with schema. ");
            if (!missing.isEmpty()) {
                sb.append("Missing keys: ").append(missing).append(". ");
            }
            if (!extra.isEmpty()) {
                sb.append("Extra keys: ").append(extra).append(". ");
            }
            throw new IllegalStateException(sb.toString());
        }

        for (String key : schemaKeys) {
            String expected = fromSchema.getProperty(key);
            String actual = fallback.getProperty(key);
            if (actual == null) {
                throw new IllegalStateException("Missing key in SleuthDefaults: " + key);
            }
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Fallback default mismatch for key=" + key + ": expected=" + expected + " actual=" + actual);
            }
        }
    }
}

