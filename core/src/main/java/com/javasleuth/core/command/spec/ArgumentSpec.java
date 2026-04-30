package com.javasleuth.core.command.spec;

public final class ArgumentSpec {
    private final String name;
    private final boolean required;
    private final boolean trailing;

    private ArgumentSpec(String name, boolean required, boolean trailing) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Argument name must not be blank");
        }
        this.name = name;
        this.required = required;
        this.trailing = trailing;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static ArgumentSpec required(String name) {
        return new ArgumentSpec(name, true, false);
    }

    public static ArgumentSpec optional(String name) {
        return new ArgumentSpec(name, false, false);
    }

    public static ArgumentSpec trailing(String name) {
        return new ArgumentSpec(name, false, true);
    }

    public String getName() {
        return name;
    }

    public boolean isRequired() {
        return required;
    }

    public boolean isTrailing() {
        return trailing;
    }

    public static final class Builder {
        private final String name;
        private boolean required;
        private boolean trailing;

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

        public Builder trailing(boolean trailing) {
            this.trailing = trailing;
            return this;
        }

        public ArgumentSpec build() {
            return new ArgumentSpec(name, required, trailing);
        }
    }
}
