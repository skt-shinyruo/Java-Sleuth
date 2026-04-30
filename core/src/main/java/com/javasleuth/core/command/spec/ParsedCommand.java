package com.javasleuth.core.command.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ParsedCommand {
    private final Map<String, String> arguments;
    private final Map<String, List<String>> argumentValues;
    private final Map<String, List<Object>> options;
    private final Map<String, Boolean> explicitOptions;
    private final boolean helpRequested;
    private final String subcommandName;

    ParsedCommand(Map<String, String> arguments, Map<String, List<Object>> options, boolean helpRequested) {
        this(arguments, new LinkedHashMap<String, List<String>>(), options, new LinkedHashMap<String, Boolean>(), helpRequested, null);
    }

    ParsedCommand(Map<String, String> arguments, Map<String, List<String>> argumentValues, Map<String, List<Object>> options, Map<String, Boolean> explicitOptions, boolean helpRequested, String subcommandName) {
        this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        this.argumentValues = immutableStringListMap(argumentValues);
        this.options = immutableOptionMap(options);
        this.explicitOptions = Collections.unmodifiableMap(new LinkedHashMap<>(explicitOptions));
        this.helpRequested = helpRequested;
        this.subcommandName = subcommandName;
    }

    public String argument(String name) {
        return arguments.get(name);
    }

    public List<String> argumentValues(String name) {
        List<String> values = argumentValues.get(name);
        return values == null ? Collections.<String>emptyList() : values;
    }

    @SuppressWarnings("unchecked")
    public <T> T option(String name) {
        List<Object> values = options.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return (T) values.get(values.size() - 1);
    }

    public Integer intOption(String name) {
        Object value = option(name);
        return value == null ? null : (Integer) value;
    }

    public Long longOption(String name) {
        Object value = option(name);
        return value == null ? null : (Long) value;
    }

    public Boolean booleanOption(String name) {
        Object value = option(name);
        return value == null ? null : (Boolean) value;
    }

    public String stringOption(String name) {
        Object value = option(name);
        return value == null ? null : (String) value;
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> optionValues(String name) {
        List<Object> values = options.get(name);
        return values == null ? Collections.emptyList() : (List<T>) values;
    }

    public List<String> stringOptionValues(String name) {
        return optionValues(name);
    }

    public boolean isOptionExplicit(String name) {
        return Boolean.TRUE.equals(explicitOptions.get(name));
    }

    public boolean isHelpRequested() {
        return helpRequested;
    }

    public String subcommandName() {
        return subcommandName;
    }

    public Map<String, String> getArguments() {
        return arguments;
    }

    public Map<String, List<Object>> getOptions() {
        return options;
    }

    ParsedCommand withSubcommandName(String name) {
        return new ParsedCommand(arguments, argumentValues, options, explicitOptions, helpRequested, name);
    }

    private static Map<String, List<String>> immutableStringListMap(Map<String, List<String>> input) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : input.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<Object>> immutableOptionMap(Map<String, List<Object>> input) {
        Map<String, List<Object>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : input.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }
}
