package com.javasleuth.foundation.config.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Schema 结构与默认值自校验器（构建期/CI fail-fast）。
 *
 * <p>该校验器不读取外部文件，仅校验 Schema 自身的完整性与一致性。</p>
 */
public final class ConfigSchemaValidator {
    private ConfigSchemaValidator() {}

    public static void validateOrThrow() {
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Config schema validation failed: ").append(errors.size()).append(" error(s)").append('\n');
            for (String e : errors) {
                sb.append("- ").append(e).append('\n');
            }
            throw new IllegalStateException(sb.toString());
        }
    }

    public static List<String> validate() {
        List<String> errors = new ArrayList<>();
        List<ConfigKey<?>> keys = SleuthConfigSchema.keys();
        if (keys == null || keys.isEmpty()) {
            errors.add("Schema keys list is empty");
            return errors;
        }

        Map<String, Integer> seen = new HashMap<>();
        for (ConfigKey<?> k : keys) {
            if (k == null) {
                errors.add("Schema contains null key entry");
                continue;
            }
            String name = k.getKey();
            if (name == null || name.trim().isEmpty()) {
                errors.add("Key name is blank: " + k);
                continue;
            }
            Integer prev = seen.putIfAbsent(name, 1);
            if (prev != null) {
                errors.add("Duplicate key: " + name);
            }

            if (k.getLiteralDefaultValue() == null) {
                errors.add("Missing literal default for key: " + name);
            }

            if (requiresSensitive(k) && !k.isSensitive()) {
                errors.add("Key should be marked sensitive but isn't: " + name);
            }
        }

        // 关键边界必须显式 fail-fast。
        assertFailFast(errors, SleuthConfigSchema.SECURITY_MODE);

        // 合法枚举集合必须覆盖默认值（避免默认值与 allowed 漂移）。
        assertAllowedContainsDefault(errors, SleuthConfigSchema.SECURITY_MODE);
        assertAllowedContainsDefault(errors, SleuthConfigSchema.PLUGINS_CONFLICT_STRATEGY);
        assertAllowedContainsDefault(errors, SleuthConfigSchema.LOGGING_LEVEL);
        assertAllowedContainsDefault(errors, SleuthConfigSchema.SECURITY_HMAC_SESSION_ROLE);

        return errors;
    }

    private static void assertFailFast(List<String> errors, ConfigKey<?> key) {
        if (key == null) {
            return;
        }
        if (key.getFailurePolicy() != ConfigKey.FailurePolicy.FAIL_FAST) {
            errors.add("Key must be FAIL_FAST: " + key.getKey());
        }
    }

    private static void assertAllowedContainsDefault(List<String> errors, ConfigKey<String> key) {
        if (key == null) {
            return;
        }
        String def = key.getLiteralDefaultValue();
        if (def == null) {
            return;
        }
        // allowed 校验逻辑在 ConfigKey.readString 内部，这里仅做最小检查。
        String lower = def.trim().toLowerCase(Locale.ROOT);
        try {
            // 通过读取一个空 ConfigView 无法触发 allowed 检查；因此在这里对常见关键 key 做白名单校验。
            // 该检查由 Schema 常量本身（allowedStrings）保证，若缺失会在运行时导致 fallback。
            if ("security.mode".equals(key.getKey()) && !("off".equals(lower) || "hmac".equals(lower))) {
                errors.add("security.mode default must be off|hmac: " + def);
            }
            if ("plugins.conflict.strategy".equals(key.getKey()) && !("prefer-builtin".equals(lower) || "prefer-plugin".equals(lower))) {
                errors.add("plugins.conflict.strategy default must be prefer-builtin|prefer-plugin: " + def);
            }
            if ("logging.level".equals(key.getKey()) && !("trace".equals(lower) || "debug".equals(lower) || "info".equals(lower) || "warn".equals(lower) || "error".equals(lower))) {
                errors.add("logging.level default must be TRACE|DEBUG|INFO|WARN|ERROR: " + def);
            }
            if ("security.hmac.session.role".equals(key.getKey()) && !("viewer".equals(lower) || "operator".equals(lower) || "admin".equals(lower))) {
                errors.add("security.hmac.session.role default must be viewer|operator|admin: " + def);
            }
        } catch (Exception ignore) {
            // ignore
        }
    }

    private static boolean requiresSensitive(ConfigKey<?> key) {
        if (key == null) {
            return false;
        }
        // 目前仅对字符串型配置启用“必须敏感”约束：
        // - secret/password/token 的核心风险来自可被打印/持久化的明文值
        // - 数值/布尔开关（例如 *.enabled / *.bytes）不应被误判为敏感，避免掩盖可观测性
        if (key.getValueType() != ConfigKey.ValueType.STRING) {
            return false;
        }
        String k = key.getKey().toLowerCase(Locale.ROOT);
        return k.contains("password") || k.endsWith(".secret") || k.contains(".token") || k.contains("apikey") || k.contains("api_key");
    }
}
