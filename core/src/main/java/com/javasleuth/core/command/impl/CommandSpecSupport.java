package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.CommandSpecParser;
import com.javasleuth.core.command.spec.ParsedCommand;

final class CommandSpecSupport {
    private CommandSpecSupport() {
    }

    static ParsedCommand parsed(CommandSpec spec, String[] args) {
        CommandContext context = CommandContextHolder.get();
        if (context != null && context.getParsedCommand() != null) {
            return context.getParsedCommand();
        }
        return CommandSpecParser.parse(spec, args);
    }

    static int intOptionOrArgument(ParsedCommand parsed, String optionName, String argumentName, int defaultValue) {
        if (parsed != null && parsed.isOptionExplicit(optionName)) {
            Integer value = parsed.intOption(optionName);
            return value != null ? value : defaultValue;
        }
        String raw = parsed != null ? parsed.argument(argumentName) : null;
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
