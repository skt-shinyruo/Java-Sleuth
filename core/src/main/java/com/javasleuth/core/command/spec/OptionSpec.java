package com.javasleuth.core.command.spec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OptionSpec {
    public enum Type {
        FLAG,
        STRING,
        INTEGER,
        LONG
    }

    private final String name;
    private final Type type;
    private final List<String> aliases;
    private final Object defaultValue;
    private final Long min;
    private final Long max;
    private final boolean repeatable;
    private final boolean missingValueAsEmptyString;

    private OptionSpec(Builder builder) {
        this.name = builder.name;
        this.type = builder.type;
        this.aliases = Collections.unmodifiableList(new ArrayList<>(builder.aliases));
        this.defaultValue = builder.defaultValue;
        this.min = builder.min;
        this.max = builder.max;
        this.repeatable = builder.repeatable;
        this.missingValueAsEmptyString = builder.missingValueAsEmptyString;
    }

    public static Builder flag(String name) {
        return new Builder(name, Type.FLAG);
    }

    public static Builder string(String name) {
        return new Builder(name, Type.STRING);
    }

    public static Builder integer(String name) {
        return new Builder(name, Type.INTEGER);
    }

    public static Builder longNumber(String name) {
        return new Builder(name, Type.LONG);
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public Long getMin() {
        return min;
    }

    public Long getMax() {
        return max;
    }

    public boolean isRepeatable() {
        return repeatable;
    }

    public boolean isMissingValueAsEmptyString() {
        return missingValueAsEmptyString;
    }

    public boolean hasRange() {
        return min != null && max != null;
    }

    public static final class Builder {
        private final String name;
        private final Type type;
        private final List<String> aliases = new ArrayList<>();
        private Object defaultValue;
        private boolean defaultValueSet;
        private Long min;
        private Long max;
        private boolean repeatable;
        private boolean missingValueAsEmptyString;

        private Builder(String name, Type type) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Option name must not be blank");
            }
            this.name = name;
            this.type = type;
        }

        public Builder alias(String alias) {
            if (alias == null || alias.trim().isEmpty()) {
                throw new IllegalArgumentException("Option alias must not be blank");
            }
            aliases.add(alias);
            return this;
        }

        public Builder defaultValue(Object value) {
            this.defaultValue = value;
            this.defaultValueSet = true;
            return this;
        }

        public Builder range(long min, long max) {
            this.min = min;
            this.max = max;
            return this;
        }

        public Builder repeatable(boolean repeatable) {
            this.repeatable = repeatable;
            return this;
        }

        public Builder missingValueAsEmptyString() {
            this.missingValueAsEmptyString = true;
            return this;
        }

        public OptionSpec build() {
            validateMissingValueConfiguration();
            validateRangeConfiguration();
            if (defaultValueSet) {
                defaultValue = normalizeDefaultValue(defaultValue);
                validateDefaultRange();
            }
            return new OptionSpec(this);
        }

        private void validateMissingValueConfiguration() {
            if (missingValueAsEmptyString && type != Type.STRING) {
                throw new IllegalArgumentException("Missing value as empty string is only supported for string options: " + name);
            }
        }

        private void validateRangeConfiguration() {
            if (min == null && max == null) {
                return;
            }
            if (type != Type.INTEGER && type != Type.LONG) {
                throw new IllegalArgumentException("Range is only supported for numeric options: " + name);
            }
            if (min > max) {
                throw new IllegalArgumentException("Range minimum must not exceed maximum for option " + name);
            }
        }

        private Object normalizeDefaultValue(Object value) {
            if (value == null) {
                throw new IllegalArgumentException("Default value for option " + name + " must not be null");
            }
            if (type == Type.FLAG) {
                if (value instanceof Boolean) {
                    return value;
                }
                throw invalidDefaultValue();
            }
            if (type == Type.STRING) {
                if (value instanceof String) {
                    return value;
                }
                throw invalidDefaultValue();
            }
            if (type == Type.INTEGER) {
                try {
                    return Integer.valueOf(toWholeNumber(value).intValueExact());
                } catch (ArithmeticException e) {
                    throw invalidDefaultValue();
                }
            }
            if (type == Type.LONG) {
                try {
                    return Long.valueOf(toWholeNumber(value).longValueExact());
                } catch (ArithmeticException e) {
                    throw invalidDefaultValue();
                }
            }
            throw invalidDefaultValue();
        }

        private BigDecimal toWholeNumber(Object value) {
            if (!(value instanceof Number)) {
                throw invalidDefaultValue();
            }
            BigDecimal decimal;
            try {
                if (value instanceof Float || value instanceof Double) {
                    decimal = BigDecimal.valueOf(((Number) value).doubleValue());
                } else {
                    decimal = new BigDecimal(value.toString());
                }
            } catch (NumberFormatException e) {
                throw invalidDefaultValue();
            }
            try {
                return new BigDecimal(decimal.toBigIntegerExact());
            } catch (ArithmeticException e) {
                throw invalidDefaultValue();
            }
        }

        private void validateDefaultRange() {
            if (!hasConfiguredRange()) {
                return;
            }
            long value = ((Number) defaultValue).longValue();
            if (value < min || value > max) {
                throw new IllegalArgumentException("Default value for option " + name + " must be between " + min + " and " + max);
            }
        }

        private boolean hasConfiguredRange() {
            return min != null && max != null;
        }

        private IllegalArgumentException invalidDefaultValue() {
            return new IllegalArgumentException("Default value for option " + name + " is not valid for type " + type);
        }
    }
}
