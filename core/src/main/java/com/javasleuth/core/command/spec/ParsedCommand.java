package com.javasleuth.core.command.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ParsedCommand {
    private final Map<String, String> arguments;
    private final Map<String, List<Object>> options;
    private final boolean helpRequested;

    ParsedCommand(Map<String, String> arguments, Map<String, List<Object>> options, boolean helpRequested) {
        this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        this.options = immutableOptionMap(options);
        this.helpRequested = helpRequested;
    }

    public String argument(String name) {
        return arguments.get(name);
    }

    public Object option(String name) {
        List<Object> values = options.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(values.size() - 1);
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

    public List<Object> optionValues(String name) {
        List<Object> values = options.get(name);
        return values == null ? Collections.emptyList() : values;
    }

    public boolean isHelpRequested() {
        return helpRequested;
    }

    public Map<String, String> getArguments() {
        return arguments;
    }

    public Map<String, List<Object>> getOptions() {
        return options;
    }

    private static Map<String, List<Object>> immutableOptionMap(Map<String, List<Object>> input) {
        Map<String, List<Object>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : input.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }
}
