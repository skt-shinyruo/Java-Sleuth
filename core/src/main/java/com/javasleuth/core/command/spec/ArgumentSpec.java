package com.javasleuth.core.command.spec;

public final class ArgumentSpec {
    private final String name;
    private final boolean required;

    private ArgumentSpec(String name, boolean required) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Argument name must not be blank");
        }
        this.name = name;
        this.required = required;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static ArgumentSpec required(String name) {
        return new ArgumentSpec(name, true);
    }

    public static ArgumentSpec optional(String name) {
        return new ArgumentSpec(name, false);
    }

    public String getName() {
        return name;
    }

    public boolean isRequired() {
        return required;
    }

    public static final class Builder {
        private final String name;
        private boolean required;

        private Builder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Argument name must not be blank");
            }
            this.name = name;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public ArgumentSpec build() {
            return new ArgumentSpec(name, required);
        }
    }
}
