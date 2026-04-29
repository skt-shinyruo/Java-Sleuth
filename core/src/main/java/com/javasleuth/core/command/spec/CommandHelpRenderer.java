package com.javasleuth.core.command.spec;

import java.util.ArrayList;
import java.util.List;

public final class CommandHelpRenderer {
    private CommandHelpRenderer() {
    }

    public static String render(CommandSpec spec) {
        StringBuilder out = new StringBuilder();
        out.append(spec.getName()).append('\n');
        if (notBlank(spec.getDescription())) {
            out.append(spec.getDescription()).append('\n');
        }
        if (notBlank(spec.getUsage())) {
            out.append('\n').append("Usage: ").append(spec.getUsage()).append('\n');
        }
        if (!spec.getArguments().isEmpty()) {
            out.append('\n').append("Arguments:").append('\n');
            for (ArgumentSpec argument : spec.getArguments()) {
                out.append("  ").append(argument.getName());
                out.append(argument.isRequired() ? " (required)" : " (optional)");
                out.append('\n');
            }
        }
        if (!spec.getOptions().isEmpty()) {
            out.append('\n').append("Options:").append('\n');
            for (OptionSpec option : spec.getOptions()) {
                out.append("  ").append(optionNames(option));
                String details = optionDetails(option);
                if (notBlank(details)) {
                    out.append(" (").append(details).append(')');
                }
                out.append('\n');
            }
        }
        if (!spec.getSubcommands().isEmpty()) {
            out.append('\n').append("Subcommands:").append('\n');
            for (SubcommandSpec subcommand : spec.getSubcommands()) {
                out.append("  ").append(subcommand.getName());
                if (notBlank(subcommand.getDescription())) {
                    out.append(" - ").append(subcommand.getDescription());
                }
                out.append('\n');
            }
        }
        if (!spec.getExamples().isEmpty()) {
            out.append('\n').append("Examples:").append('\n');
            for (String example : spec.getExamples()) {
                out.append("  ").append(example).append('\n');
            }
        }
        return out.toString();
    }

    private static String optionNames(OptionSpec option) {
        List<String> orderedNames = new ArrayList<>();
        orderedNames.add("--" + option.getName());
        for (String alias : option.getAliases()) {
            if (!orderedNames.contains(alias)) {
                orderedNames.add(alias);
            }
        }
        StringBuilder names = new StringBuilder();
        for (String name : orderedNames) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(name);
        }
        return names.toString();
    }

    private static String optionDetails(OptionSpec option) {
        StringBuilder details = new StringBuilder();
        if (option.getDefaultValue() != null) {
            details.append("default: ").append(option.getDefaultValue());
        }
        if (option.hasRange()) {
            if (details.length() > 0) {
                details.append(", ");
            }
            details.append("range: ").append(option.getMin()).append("..").append(option.getMax());
        }
        if (option.isRepeatable()) {
            if (details.length() > 0) {
                details.append(", ");
            }
            details.append("repeatable");
        }
        return details.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
