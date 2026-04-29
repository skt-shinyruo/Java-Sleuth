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
}
