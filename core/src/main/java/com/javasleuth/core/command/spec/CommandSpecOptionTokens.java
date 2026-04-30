package com.javasleuth.core.command.spec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CommandSpecOptionTokens {
    private CommandSpecOptionTokens() {
    }

    public static String[] removeOptionTokens(String[] args, CommandSpec spec, String optionName) {
        if (args == null || args.length == 0) {
            return args;
        }
        Set<String> tokens = optionTokens(spec, optionName);
        if (tokens.isEmpty()) {
            return args;
        }
        List<String> out = new ArrayList<>();
        for (String arg : args) {
            if (arg == null || tokens.contains(arg)) {
                continue;
            }
            out.add(arg);
        }
        return out.toArray(new String[0]);
    }

    public static boolean hasOptionToken(String[] args, CommandSpec spec, String optionName) {
        if (args == null || args.length == 0) {
            return false;
        }
        Set<String> tokens = optionTokens(spec, optionName);
        if (tokens.isEmpty()) {
            return false;
        }
        for (String arg : args) {
            if (tokens.contains(arg)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> optionTokens(CommandSpec spec, String optionName) {
        Set<String> tokens = new LinkedHashSet<>();
        if (spec == null || optionName == null || optionName.trim().isEmpty()) {
            return tokens;
        }
        for (OptionSpec option : spec.getOptions()) {
            if (!optionName.equals(option.getName())) {
                continue;
            }
            tokens.add("--" + option.getName());
            tokens.addAll(option.getAliases());
            break;
        }
        return tokens;
    }
}
