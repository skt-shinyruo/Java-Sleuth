package com.javasleuth.core.command.spec;

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

    private OptionSpec(Builder builder) {
        this.name = builder.name;
        this.type = builder.type;
        this.aliases = Collections.unmodifiableList(new ArrayList<>(builder.aliases));
        this.defaultValue = builder.defaultValue;
        this.min = builder.min;
        this.max = builder.max;
        this.repeatable = builder.repeatable;
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

    public boolean hasRange() {
        return min != null && max != null;
    }

    public static final class Builder {
        private final String name;
        private final Type type;
        private final List<String> aliases = new ArrayList<>();
        private Object defaultValue;
        private Long min;
        private Long max;
        private boolean repeatable;

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

        public OptionSpec build() {
            return new OptionSpec(this);
        }
    }
}
