package com.javasleuth.core.command.spec;

import com.javasleuth.foundation.security.CommandMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CommandSpec {
    private final String name;
    private final String description;
    private final String usage;
    private final CommandMeta meta;
    private final List<ArgumentSpec> arguments;
    private final List<OptionSpec> options;
    private final List<SubcommandSpec> subcommands;
    private final List<String> examples;

    private CommandSpec(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.usage = builder.usage;
        this.meta = builder.meta;
        this.arguments = immutableCopy(builder.arguments);
        this.options = immutableCopy(builder.options);
        this.subcommands = immutableCopy(builder.subcommands);
        this.examples = immutableCopy(builder.examples);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getUsage() {
        return usage;
    }

    public CommandMeta getMeta() {
        return meta;
    }

    public List<ArgumentSpec> getArguments() {
        return arguments;
    }

    public List<OptionSpec> getOptions() {
        return options;
    }

    public List<SubcommandSpec> getSubcommands() {
        return subcommands;
    }

    public List<String> getExamples() {
        return examples;
    }

    private static <T> List<T> immutableCopy(List<T> input) {
        return Collections.unmodifiableList(new ArrayList<>(input));
    }

    public static final class Builder {
        private final String name;
        private String description;
        private String usage;
        private CommandMeta meta;
        private final List<ArgumentSpec> arguments = new ArrayList<>();
        private final List<OptionSpec> options = new ArrayList<>();
        private final List<SubcommandSpec> subcommands = new ArrayList<>();
        private final List<String> examples = new ArrayList<>();

        private Builder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Command name must not be blank");
            }
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder usage(String usage) {
            this.usage = usage;
            return this;
        }

        public Builder meta(CommandMeta meta) {
            this.meta = meta;
            return this;
        }

        public Builder argument(ArgumentSpec argument) {
            if (argument != null) {
                arguments.add(argument);
            }
            return this;
        }

        public Builder option(OptionSpec option) {
            if (option != null) {
                options.add(option);
            }
            return this;
        }

        public Builder subcommand(SubcommandSpec subcommand) {
            if (subcommand != null) {
                subcommands.add(subcommand);
            }
            return this;
        }

        public Builder example(String example) {
            if (example != null && !example.trim().isEmpty()) {
                examples.add(example);
            }
            return this;
        }

        public CommandSpec build() {
            return new CommandSpec(this);
        }
    }
}
