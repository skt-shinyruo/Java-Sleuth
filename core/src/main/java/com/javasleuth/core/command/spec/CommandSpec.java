package com.javasleuth.core.command.spec;

import com.javasleuth.foundation.security.CommandMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CommandSpec {
    private final String name;
    private final String description;
    private final String usage;
    private final CommandMeta meta;
    private final List<ArgumentSpec> arguments;
    private final List<OptionSpec> options;
    private final List<OptionSpec> hiddenOptions;
    private final List<SubcommandSpec> subcommands;
    private final List<String> examples;
    private final boolean unknownSubcommandAsArgument;

    private CommandSpec(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.usage = builder.usage;
        this.meta = builder.meta;
        this.arguments = immutableCopy(builder.arguments);
        this.options = immutableCopy(builder.options);
        this.hiddenOptions = immutableCopy(builder.hiddenOptions);
        this.subcommands = immutableCopy(builder.subcommands);
        this.examples = immutableCopy(builder.examples);
        this.unknownSubcommandAsArgument = builder.unknownSubcommandAsArgument;
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

    List<OptionSpec> getParserOptions() {
        if (hiddenOptions.isEmpty()) {
            return options;
        }
        List<OptionSpec> parserOptions = new ArrayList<>(options);
        parserOptions.addAll(hiddenOptions);
        return Collections.unmodifiableList(parserOptions);
    }

    public List<SubcommandSpec> getSubcommands() {
        return subcommands;
    }

    public SubcommandSpec subcommand(String name) {
        if (name == null) {
            return null;
        }
        for (SubcommandSpec subcommand : subcommands) {
            if (name.equals(subcommand.getName())) {
                return subcommand;
            }
        }
        return null;
    }

    public List<String> getExamples() {
        return examples;
    }

    public boolean isUnknownSubcommandAsArgument() {
        return unknownSubcommandAsArgument;
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
        private final List<OptionSpec> hiddenOptions = new ArrayList<>();
        private final List<SubcommandSpec> subcommands = new ArrayList<>();
        private final List<String> examples = new ArrayList<>();
        private boolean unknownSubcommandAsArgument;

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

        public Builder hiddenOption(OptionSpec option) {
            if (option != null) {
                hiddenOptions.add(option);
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

        public Builder unknownSubcommandAsArgument(boolean unknownSubcommandAsArgument) {
            this.unknownSubcommandAsArgument = unknownSubcommandAsArgument;
            return this;
        }

        public CommandSpec build() {
            validateUniqueArguments();
            validateUniqueOptions();
            return new CommandSpec(this);
        }

        private void validateUniqueArguments() {
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < arguments.size(); i++) {
                ArgumentSpec argument = arguments.get(i);
                if (!names.add(argument.getName())) {
                    throw new IllegalArgumentException("Duplicate argument name: " + argument.getName());
                }
                if (argument.isTrailing() && i != arguments.size() - 1) {
                    throw new IllegalArgumentException("Trailing argument must be last: " + argument.getName());
                }
            }
        }

        private void validateUniqueOptions() {
            Map<String, OptionSpec> tokens = new LinkedHashMap<>();
            for (OptionSpec option : allOptions()) {
                String canonical = "--" + option.getName();
                OptionSpec previous = tokens.put(canonical, option);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate option name: " + option.getName());
                }
                Set<String> aliases = new LinkedHashSet<>();
                for (String alias : option.getAliases()) {
                    if (canonical.equals(alias)) {
                        continue;
                    }
                    if (!aliases.add(alias)) {
                        throw new IllegalArgumentException("Duplicate option alias: " + alias);
                    }
                    previous = tokens.put(alias, option);
                    if (previous != null) {
                        throw new IllegalArgumentException("Duplicate option alias: " + alias);
                    }
                }
            }
        }

        private List<OptionSpec> allOptions() {
            List<OptionSpec> allOptions = new ArrayList<>(options);
            allOptions.addAll(hiddenOptions);
            return allOptions;
        }
    }
}
