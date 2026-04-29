package com.javasleuth.core.command.spec;

public final class SubcommandSpec {
    private final String name;
    private final String description;
    private final CommandSpec spec;

    public SubcommandSpec(String name, String description, CommandSpec spec) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Subcommand name must not be blank");
        }
        this.name = name;
        this.description = description;
        this.spec = spec;
    }

    public static SubcommandSpec of(String name, String description, CommandSpec spec) {
        return new SubcommandSpec(name, description, spec);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public CommandSpec getSpec() {
        return spec;
    }
}
