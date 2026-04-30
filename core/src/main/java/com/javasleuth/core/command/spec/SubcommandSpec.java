package com.javasleuth.core.command.spec;

public final class SubcommandSpec {
    private final String name;
    private final String description;
    private final CommandSpec spec;

    public SubcommandSpec(String name, String description, CommandSpec spec) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Subcommand name must not be blank");
        }
        if (spec == null) {
            throw new IllegalArgumentException("Subcommand spec must not be null");
        }
        this.name = name;
        this.description = description;
        this.spec = spec;
    }

    public static Builder builder(String name) {
        return new Builder(name);
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

    public static final class Builder {
        private final String name;
        private String description;
        private CommandSpec spec;
        private CommandSpec.Builder specBuilder;

        private Builder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Subcommand name must not be blank");
            }
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder usage(String usage) {
            builder().usage(usage);
            return this;
        }

        public Builder spec(CommandSpec spec) {
            this.spec = spec;
            return this;
        }

        public Builder argument(ArgumentSpec argument) {
            builder().argument(argument);
            return this;
        }

        public Builder option(OptionSpec option) {
            builder().option(option);
            return this;
        }

        public Builder hiddenOption(OptionSpec option) {
            builder().hiddenOption(option);
            return this;
        }

        public Builder example(String example) {
            builder().example(example);
            return this;
        }

        public SubcommandSpec build() {
            CommandSpec actualSpec = spec != null ? spec : (specBuilder != null ? specBuilder.build() : null);
            return new SubcommandSpec(name, description, actualSpec);
        }

        private CommandSpec.Builder builder() {
            if (specBuilder == null) {
                specBuilder = CommandSpec.builder(name).description(description).usage(name + " [options]");
            }
            return specBuilder;
        }
    }
}
