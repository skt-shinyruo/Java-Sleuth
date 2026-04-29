package com.javasleuth.foundation.config.schema;

import com.javasleuth.foundation.config.ConfigOrigin;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.util.SleuthLogger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 配置键 Schema 元数据（SSOT 基元）。
 *
 * <p>该类型用于把 key/type/default/约束/失败策略等事实收敛到单处，并提供统一读取与校验语义。</p>
 */
public final class ConfigKey<T> {
    public enum ValueType {
        STRING,
        INT,
        LONG,
        DOUBLE,
        BOOLEAN
    }

    public enum FailurePolicy {
        /** 显式配置非法值时直接失败（关键兼容边界）。 */
        FAIL_FAST,
        /** 显式配置非法值时告警并回退到默认值。 */
        WARN_AND_FALLBACK,
        /** 显式配置非法值时告警并按范围 clamp（若无法 clamp 则回退默认）。 */
        CLAMP_AND_WARN
    }

    public interface DerivedDefault<T> {
        T derive(ConfigView config, ConfigOrigin origin, T literalDefault);
    }

    private final String key;
    private final ValueType valueType;
    private final String literalDefaultValue;
    private final T literalDefault;
    private final boolean sensitive;
    private final boolean requireNonBlank;
    private final FailurePolicy failurePolicy;
    private final Long minLongInclusive;
    private final Long maxLongInclusive;
    private final Double minDoubleInclusive;
    private final Double maxDoubleInclusive;
    private final Set<String> allowedStringValuesLower;
    private final DerivedDefault<T> derivedDefault;

    private ConfigKey(Builder<T> builder) {
        this.key = builder.key;
        this.valueType = builder.valueType;
        this.literalDefaultValue = builder.literalDefaultValue;
        this.literalDefault = builder.literalDefault;
        this.sensitive = builder.sensitive;
        this.requireNonBlank = builder.requireNonBlank;
        this.failurePolicy = builder.failurePolicy;
        this.minLongInclusive = builder.minLongInclusive;
        this.maxLongInclusive = builder.maxLongInclusive;
        this.minDoubleInclusive = builder.minDoubleInclusive;
        this.maxDoubleInclusive = builder.maxDoubleInclusive;
        this.allowedStringValuesLower = builder.allowedStringValuesLower;
        this.derivedDefault = builder.derivedDefault;
    }

    public static Builder<String> stringKey(String key) {
        return new Builder<>(key, ValueType.STRING);
    }

    public static Builder<Integer> intKey(String key) {
        return new Builder<>(key, ValueType.INT);
    }

    public static Builder<Long> longKey(String key) {
        return new Builder<>(key, ValueType.LONG);
    }

    public static Builder<Double> doubleKey(String key) {
        return new Builder<>(key, ValueType.DOUBLE);
    }

    public static Builder<Boolean> booleanKey(String key) {
        return new Builder<>(key, ValueType.BOOLEAN);
    }

    public String getKey() {
        return key;
    }

    public ValueType getValueType() {
        return valueType;
    }

    public String getLiteralDefaultValue() {
        return literalDefaultValue;
    }

    public T getLiteralDefault() {
        return literalDefault;
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public FailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    public boolean isDerived() {
        return derivedDefault != null;
    }

    public boolean isExplicit(ConfigView config) {
        if (config == null) {
            return false;
        }
        ConfigOrigin origin = config.getOrigin(key);
        return isExplicitOrigin(origin);
    }

    public T read(ConfigView config) {
        if (config == null) {
            return literalDefault;
        }

        ConfigOrigin origin = config.getOrigin(key);
        boolean explicit = isExplicitOrigin(origin);

        if (derivedDefault != null && !explicit) {
            try {
                T derived = derivedDefault.derive(config, origin, literalDefault);
                if (derived != null) {
                    return derived;
                }
            } catch (RuntimeException e) {
                // 派生默认失败不应导致崩溃（除非 key 设计为 fail-fast）；回退到字面默认。
                SleuthLogger.debug("Derived default failed for key=" + key + ": " + e.getMessage(), e);
                return literalDefault;
            }
        }

        String raw = config.getString(key, null);
        if (raw == null) {
            return literalDefault;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() && valueType != ValueType.STRING) {
            return onInvalid(config, origin, raw, "empty", literalDefault);
        }

        switch (valueType) {
            case STRING:
                return (T) readString(config, origin, trimmed);
            case INT:
                return (T) readInt(config, origin, trimmed);
            case LONG:
                return (T) readLong(config, origin, trimmed);
            case DOUBLE:
                return (T) readDouble(config, origin, trimmed);
            case BOOLEAN:
                return (T) readBoolean(config, origin, trimmed);
            default:
                return literalDefault;
        }
    }

    public ConfigValidationResult validateRuntimeValue(String rawValue) {
        String raw = rawValue == null ? "" : rawValue.trim();
        String displayValue = displayRaw(rawValue);
        if (raw.isEmpty() && valueType != ValueType.STRING) {
            return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (empty)", sensitive);
        }
        try {
            switch (valueType) {
                case STRING:
                    if (requireNonBlank && raw.trim().isEmpty()) {
                        return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (blank)", sensitive);
                    }
                    if (allowedStringValuesLower != null && !allowedStringValuesLower.isEmpty()) {
                        String lower = raw.toLowerCase(Locale.ROOT);
                        if (!allowedStringValuesLower.contains(lower)) {
                            return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (unsupported)", sensitive);
                        }
                    }
                    return ConfigValidationResult.ok(raw, sensitive);
                case INT:
                    int intValue = Integer.parseInt(raw);
                    if (minLongInclusive != null && intValue < minLongInclusive.longValue()) {
                        return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (out_of_range)", sensitive);
                    }
                    if (maxLongInclusive != null && intValue > maxLongInclusive.longValue()) {
                        return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (out_of_range)", sensitive);
                    }
                    return ConfigValidationResult.ok(String.valueOf(intValue), sensitive);
                case LONG:
                    long longValue = Long.parseLong(raw);
                    if (minLongInclusive != null && longValue < minLongInclusive.longValue()) {
                        return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (out_of_range)", sensitive);
                    }
                    if (maxLongInclusive != null && longValue > maxLongInclusive.longValue()) {
                        return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (out_of_range)", sensitive);
                    }
                    return ConfigValidationResult.ok(String.valueOf(longValue), sensitive);
                case DOUBLE:
                    double doubleValue = Double.parseDouble(raw);
                    if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
                        return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (not_finite)", sensitive);
                    }
                    if (minDoubleInclusive != null && doubleValue < minDoubleInclusive.doubleValue()) {
                        return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (out_of_range)", sensitive);
                    }
                    if (maxDoubleInclusive != null && doubleValue > maxDoubleInclusive.doubleValue()) {
                        return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (out_of_range)", sensitive);
                    }
                    return ConfigValidationResult.ok(String.valueOf(doubleValue), sensitive);
                case BOOLEAN:
                    String lower = raw.toLowerCase(Locale.ROOT);
                    if (!"true".equals(lower) && !"false".equals(lower)) {
                        return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (not_boolean)", sensitive);
                    }
                    return ConfigValidationResult.ok(lower, sensitive);
                default:
                    return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (unsupported_type)", sensitive);
            }
        } catch (NumberFormatException e) {
            return ConfigValidationResult.invalid("Invalid config " + key + "=" + displayValue + " (not_" + valueType.name().toLowerCase(Locale.ROOT) + ")", sensitive);
        }
    }

    private String displayRaw(String raw) {
        return sensitive ? "<sensitive>" : String.valueOf(raw);
    }

    private String readString(ConfigView config, ConfigOrigin origin, String value) {
        String v = value;
        if (v == null) {
            return (String) literalDefault;
        }
        if (requireNonBlank && v.trim().isEmpty()) {
            return (String) onInvalid(config, origin, v, "blank", (String) literalDefault);
        }
        if (allowedStringValuesLower != null && !allowedStringValuesLower.isEmpty()) {
            String lower = v.trim().toLowerCase(Locale.ROOT);
            if (!allowedStringValuesLower.contains(lower)) {
                return (String) onInvalid(config, origin, v, "unsupported", (String) literalDefault);
            }
        }
        return v.trim();
    }

    private Integer readInt(ConfigView config, ConfigOrigin origin, String value) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return (Integer) onInvalid(config, origin, value, "not_int", (Integer) literalDefault);
        }
        long v = parsed;
        if (minLongInclusive != null && v < minLongInclusive) {
            return handleLongOutOfRange(config, origin, value, v).intValue();
        }
        if (maxLongInclusive != null && v > maxLongInclusive) {
            return handleLongOutOfRange(config, origin, value, v).intValue();
        }
        return parsed;
    }

    private Long readLong(ConfigView config, ConfigOrigin origin, String value) {
        final long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            return (Long) onInvalid(config, origin, value, "not_long", (Long) literalDefault);
        }
        if (minLongInclusive != null && parsed < minLongInclusive) {
            return handleLongOutOfRange(config, origin, value, parsed);
        }
        if (maxLongInclusive != null && parsed > maxLongInclusive) {
            return handleLongOutOfRange(config, origin, value, parsed);
        }
        return parsed;
    }

    private Double readDouble(ConfigView config, ConfigOrigin origin, String value) {
        final double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return (Double) onInvalid(config, origin, value, "not_double", (Double) literalDefault);
        }
        if (minDoubleInclusive != null && parsed < minDoubleInclusive) {
            return handleDoubleOutOfRange(config, origin, value, parsed);
        }
        if (maxDoubleInclusive != null && parsed > maxDoubleInclusive) {
            return handleDoubleOutOfRange(config, origin, value, parsed);
        }
        return parsed;
    }

    private Boolean readBoolean(ConfigView config, ConfigOrigin origin, String value) {
        if (value == null) {
            return (Boolean) literalDefault;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(v)) {
            return Boolean.TRUE;
        }
        if ("false".equals(v)) {
            return Boolean.FALSE;
        }
        return (Boolean) onInvalid(config, origin, value, "not_boolean", (Boolean) literalDefault);
    }

    private Long handleLongOutOfRange(ConfigView config, ConfigOrigin origin, String raw, long parsed) {
        boolean explicit = isExplicitOrigin(origin);
        if (explicit && failurePolicy == FailurePolicy.FAIL_FAST) {
            throw new IllegalArgumentException("Invalid config " + key + "=" + raw + " (out of range)");
        }
        if (explicit && failurePolicy == FailurePolicy.CLAMP_AND_WARN) {
            long clamped = parsed;
            if (minLongInclusive != null) {
                clamped = Math.max(clamped, minLongInclusive);
            }
            if (maxLongInclusive != null) {
                clamped = Math.min(clamped, maxLongInclusive);
            }
            warnInvalid(explicit, raw, "clamped_to_" + clamped);
            return clamped;
        }
        Long fallbackLong = null;
        if (literalDefault instanceof Long) {
            fallbackLong = (Long) literalDefault;
        } else if (literalDefault instanceof Integer) {
            fallbackLong = ((Integer) literalDefault).longValue();
        }
        if (fallbackLong == null) {
            fallbackLong = minLongInclusive != null ? minLongInclusive : 0L;
        }
        return onInvalid(config, origin, raw, "out_of_range", fallbackLong);
    }

    private Double handleDoubleOutOfRange(ConfigView config, ConfigOrigin origin, String raw, double parsed) {
        boolean explicit = isExplicitOrigin(origin);
        if (explicit && failurePolicy == FailurePolicy.FAIL_FAST) {
            throw new IllegalArgumentException("Invalid config " + key + "=" + raw + " (out of range)");
        }
        if (explicit && failurePolicy == FailurePolicy.CLAMP_AND_WARN) {
            double clamped = parsed;
            if (minDoubleInclusive != null) {
                clamped = Math.max(clamped, minDoubleInclusive);
            }
            if (maxDoubleInclusive != null) {
                clamped = Math.min(clamped, maxDoubleInclusive);
            }
            warnInvalid(explicit, raw, "clamped_to_" + clamped);
            return clamped;
        }
        return (Double) onInvalid(config, origin, raw, "out_of_range", (Double) literalDefault);
    }

    private <V> V onInvalid(ConfigView config, ConfigOrigin origin, String raw, String reason, V fallback) {
        boolean explicit = isExplicitOrigin(origin);
        if (explicit && failurePolicy == FailurePolicy.FAIL_FAST) {
            throw new IllegalArgumentException("Invalid config " + key + "=" + raw + " (" + reason + ")");
        }
        warnInvalid(explicit, raw, reason);
        return fallback;
    }

    private void warnInvalid(boolean explicit, String raw, String reason) {
        if (!explicit) {
            return;
        }
        if (failurePolicy == FailurePolicy.WARN_AND_FALLBACK || failurePolicy == FailurePolicy.CLAMP_AND_WARN) {
            SleuthLogger.warn("Config normalized: key=" + key + " raw=" + raw + " reason=" + reason +
                " fallback=" + literalDefaultValue + " policy=" + failurePolicy.name());
        }
    }

    private static boolean isExplicitOrigin(ConfigOrigin origin) {
        return origin == ConfigOrigin.FILE || origin == ConfigOrigin.SYSTEM_PROPERTY || origin == ConfigOrigin.RUNTIME_OVERRIDE;
    }

    public static final class Builder<T> {
        private final String key;
        private final ValueType valueType;
        private String literalDefaultValue;
        private T literalDefault;
        private boolean sensitive;
        private boolean requireNonBlank;
        private FailurePolicy failurePolicy = FailurePolicy.WARN_AND_FALLBACK;
        private Long minLongInclusive;
        private Long maxLongInclusive;
        private Double minDoubleInclusive;
        private Double maxDoubleInclusive;
        private Set<String> allowedStringValuesLower;
        private DerivedDefault<T> derivedDefault;

        private Builder(String key, ValueType valueType) {
            this.key = key;
            this.valueType = valueType;
        }

        public Builder<T> defaultValue(T value) {
            this.literalDefault = value;
            this.literalDefaultValue = stringifyDefault(valueType, value);
            return this;
        }

        public Builder<T> sensitive() {
            this.sensitive = true;
            return this;
        }

        public Builder<T> failurePolicy(FailurePolicy policy) {
            if (policy != null) {
                this.failurePolicy = policy;
            }
            return this;
        }

        public Builder<T> nonBlank() {
            this.requireNonBlank = true;
            return this;
        }

        public Builder<T> longRange(long minInclusive, long maxInclusive) {
            this.minLongInclusive = minInclusive;
            this.maxLongInclusive = maxInclusive;
            return this;
        }

        public Builder<T> longMin(long minInclusive) {
            this.minLongInclusive = minInclusive;
            return this;
        }

        public Builder<T> doubleRange(double minInclusive, double maxInclusive) {
            this.minDoubleInclusive = minInclusive;
            this.maxDoubleInclusive = maxInclusive;
            return this;
        }

        public Builder<T> allowedStrings(String... allowed) {
            if (allowed == null || allowed.length == 0) {
                this.allowedStringValuesLower = null;
                return this;
            }
            Set<String> set = new HashSet<>();
            for (String v : allowed) {
                if (v == null) {
                    continue;
                }
                String t = v.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) {
                    set.add(t);
                }
            }
            this.allowedStringValuesLower = set;
            return this;
        }

        public Builder<T> derivedDefault(DerivedDefault<T> derivedDefault) {
            this.derivedDefault = derivedDefault;
            return this;
        }

        public ConfigKey<T> build() {
            return new ConfigKey<>(this);
        }
    }

    static String stringifyDefault(ValueType type, Object v) {
        if (v == null) {
            return "";
        }
        switch (type) {
            case BOOLEAN:
                return Boolean.TRUE.equals(v) ? "true" : "false";
            case DOUBLE:
                // properties 以字符串为 SSOT：避免科学计数法，保留 Java 默认 toString 行为。
                return String.valueOf(v);
            default:
                return String.valueOf(v);
        }
    }

    @Override
    public String toString() {
        return "ConfigKey{" +
            "key='" + key + '\'' +
            ", valueType=" + valueType +
            ", literalDefaultValue='" + literalDefaultValue + '\'' +
            ", sensitive=" + sensitive +
            ", requireNonBlank=" + requireNonBlank +
            ", failurePolicy=" + failurePolicy +
            ", longRange=" + Arrays.asList(minLongInclusive, maxLongInclusive) +
            ", doubleRange=" + Arrays.asList(minDoubleInclusive, maxDoubleInclusive) +
            ", allowedStringValuesLower=" + allowedStringValuesLower +
            ", derived=" + (derivedDefault != null) +
            '}';
    }
}
