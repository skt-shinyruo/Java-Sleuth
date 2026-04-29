package com.javasleuth.core.command.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CommandSpecParser {
    private CommandSpecParser() {
    }

    public static ParsedCommand parse(CommandSpec spec, String[] args) {
        Map<String, OptionSpec> optionsByToken = buildOptionIndex(spec);
        Map<String, String> arguments = new LinkedHashMap<>();
        Map<String, List<Object>> options = defaultOptions(spec);
        Map<String, Boolean> explicitOptions = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        boolean helpRequested = false;

        String[] actualArgs = args == null ? new String[0] : args;
        for (int i = 1; i < actualArgs.length; i++) {
            String token = actualArgs[i];
            if (isHelpToken(token)) {
                helpRequested = true;
                continue;
            }
            if (token != null && token.startsWith("-")) {
                ParsedOptionToken parsedToken = splitOptionToken(token);
                OptionSpec option = optionsByToken.get(parsedToken.name);
                if (option == null) {
                    throw new CommandSpecParseException("E_ARGS_UNKNOWN", "Unknown option " + parsedToken.name);
                }
                if (!option.isRepeatable() && explicitOptions.containsKey(option.getName())) {
                    throw new CommandSpecParseException("E_ARGS_DUPLICATE", "Option " + parsedToken.name + " cannot be repeated");
                }
                Object value;
                if (option.getType() == OptionSpec.Type.FLAG) {
                    if (parsedToken.value != null) {
                        throw new CommandSpecParseException("E_ARGS_INVALID", "Flag " + parsedToken.name + " does not accept a value");
                    }
                    value = Boolean.TRUE;
                } else {
                    String rawValue = parsedToken.value;
                    if (rawValue == null) {
                        if (i + 1 >= actualArgs.length || isMissingValueToken(option, actualArgs[i + 1])) {
                            throw new CommandSpecParseException("E_ARGS_MISSING", "Missing value for option " + parsedToken.name);
                        }
                        rawValue = actualArgs[++i];
                    }
                    value = convert(option, rawValue);
                }
                addOptionValue(options, option.getName(), value);
                explicitOptions.put(option.getName(), Boolean.TRUE);
            } else {
                positional.add(token);
            }
        }

        if (!helpRequested) {
            bindArguments(spec, positional, arguments);
        }
        return new ParsedCommand(arguments, options, helpRequested);
    }

    private static Map<String, OptionSpec> buildOptionIndex(CommandSpec spec) {
        Map<String, OptionSpec> index = new LinkedHashMap<>();
        for (OptionSpec option : spec.getOptions()) {
            putOptionToken(index, "--" + option.getName(), option);
            for (String alias : option.getAliases()) {
                putOptionToken(index, alias, option);
            }
        }
        return index;
    }

    private static void putOptionToken(Map<String, OptionSpec> index, String token, OptionSpec option) {
        OptionSpec previous = index.put(token, option);
        if (previous != null && previous != option) {
            throw new IllegalArgumentException("Duplicate option token in spec: " + token);
        }
    }

    private static Map<String, List<Object>> defaultOptions(CommandSpec spec) {
        Map<String, List<Object>> values = new LinkedHashMap<>();
        for (OptionSpec option : spec.getOptions()) {
            values.put(option.getName(), new ArrayList<Object>());
            if (option.getDefaultValue() != null) {
                addOptionValue(values, option.getName(), option.getDefaultValue());
            }
        }
        return values;
    }

    private static void bindArguments(CommandSpec spec, List<String> positional, Map<String, String> arguments) {
        List<ArgumentSpec> specs = spec.getArguments();
        for (int i = 0; i < specs.size(); i++) {
            ArgumentSpec argument = specs.get(i);
            if (i < positional.size()) {
                arguments.put(argument.getName(), positional.get(i));
            } else if (argument.isRequired()) {
                throw new CommandSpecParseException("E_ARGS_MISSING", "Missing argument " + argument.getName());
            }
        }
        if (positional.size() > specs.size()) {
            throw new CommandSpecParseException("E_ARGS_INVALID", "Too many arguments");
        }
    }

    private static Object convert(OptionSpec option, String value) {
        try {
            if (option.getType() == OptionSpec.Type.STRING) {
                return value;
            }
            if (option.getType() == OptionSpec.Type.INTEGER) {
                int parsed = Integer.parseInt(value);
                checkRange(option, parsed);
                return parsed;
            }
            if (option.getType() == OptionSpec.Type.LONG) {
                long parsed = Long.parseLong(value);
                checkRange(option, parsed);
                return parsed;
            }
            return value;
        } catch (NumberFormatException e) {
            throw new CommandSpecParseException("E_ARGS_INVALID", "Invalid value for option " + option.getName());
        }
    }

    private static void checkRange(OptionSpec option, long value) {
        if (option.hasRange() && (value < option.getMin() || value > option.getMax())) {
            throw new CommandSpecParseException("E_ARGS_RANGE", "Value for option " + option.getName() + " must be between "
                + option.getMin() + " and " + option.getMax());
        }
    }

    private static void addOptionValue(Map<String, List<Object>> options, String name, Object value) {
        List<Object> values = options.get(name);
        if (values == null) {
            values = new ArrayList<>();
            options.put(name, values);
        }
        values.add(value);
    }

    private static boolean isHelpToken(String token) {
        return "-h".equals(token) || "--help".equals(token) || "help".equals(token);
    }

    private static boolean isMissingValueToken(OptionSpec option, String token) {
        return token == null || (token.startsWith("-") && !isNegativeNumericLiteral(option, token));
    }

    private static boolean isNegativeNumericLiteral(OptionSpec option, String token) {
        if (token == null || token.length() <= 1) {
            return false;
        }
        if (option.getType() != OptionSpec.Type.INTEGER && option.getType() != OptionSpec.Type.LONG) {
            return false;
        }
        return token.matches("-[0-9]+");
    }

    private static ParsedOptionToken splitOptionToken(String token) {
        int equals = token.indexOf('=');
        if (equals < 0) {
            return new ParsedOptionToken(token, null);
        }
        return new ParsedOptionToken(token.substring(0, equals), token.substring(equals + 1));
    }

    private static final class ParsedOptionToken {
        private final String name;
        private final String value;

        private ParsedOptionToken(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
