package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.foundation.security.SecurityValidator;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;

public class SysPropCommand implements Command {
    @SuppressWarnings("unused")
    private final Instrumentation instrumentation;

    public SysPropCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length > 1 && "--help".equals(args[1])) {
            return getHelpText();
        }

        if (args.length == 1) {
            return listAllProperties();
        }

        if (args.length >= 2 && "set".equalsIgnoreCase(args[1])) {
            if (args.length < 4) {
                return "Usage: sysprop set <key> <value>";
            }
            if (args.length > 4) {
                return "Invalid arguments. Note: sysprop set currently supports single-token values (no spaces).";
            }
            String key = args[2];
            String value = args[3];
            if (!isValidPropertyKey(key)) {
                return "Invalid property key format";
            }
            if (!isValidPropertyValue(value)) {
                return "Invalid property value format";
            }
            return setProperty(key, value);
        }

        if (args.length == 2) {
            String key = args[1];
            if (key.contains("*")) {
                return searchProperties(key);
            }
            return getProperty(key);
        }

        return "Invalid arguments. Use: sysprop [key|pattern] | sysprop set <key> <value> | sysprop --help";
    }

    private String listAllProperties() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== System Properties ===\n");

        Properties props = System.getProperties();
        Map<String, String> sortedProps = new TreeMap<>();

        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            sortedProps.put(entry.getKey().toString(), entry.getValue().toString());
        }

        sb.append(String.format("Total properties: %d\n\n", sortedProps.size()));

        for (Map.Entry<String, String> entry : sortedProps.entrySet()) {
            String key = entry.getKey();
            String value = SecurityValidator.maskSensitiveValue(key, entry.getValue());

            // Truncate long values for readability
            if (value.length() > 100) {
                value = value.substring(0, 97) + "...";
            }

            sb.append(String.format("%-30s = %s\n", key, value));
        }

        return sb.toString();
    }

    private String getProperty(String key) {
        String value = System.getProperty(key);
        if (value == null) {
            return String.format("System property '%s' not found", key);
        } else {
            value = SecurityValidator.maskSensitiveValue(key, value);
            return String.format("=== System Property ===\n" +
                               "Key: %s\n" +
                               "Value: %s\n", key, value);
        }
    }

    private String searchProperties(String pattern) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== System Properties Search ===\n");
        sb.append("Pattern: ").append(pattern).append("\n\n");

        // Convert wildcard pattern to regex
        String regex = pattern.replace("*", ".*").replace("?", ".?");
        Pattern compiledPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);

        Properties props = System.getProperties();
        Map<String, String> matchingProps = new TreeMap<>();

        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            String key = entry.getKey().toString();
            if (compiledPattern.matcher(key).matches()) {
                matchingProps.put(key, entry.getValue().toString());
            }
        }

        if (matchingProps.isEmpty()) {
            sb.append("No properties found matching pattern: ").append(pattern);
        } else {
            sb.append(String.format("Found %d matching properties:\n\n", matchingProps.size()));

            for (Map.Entry<String, String> entry : matchingProps.entrySet()) {
                String key = entry.getKey();
                String value = SecurityValidator.maskSensitiveValue(key, entry.getValue());

                // Truncate long values for readability
                if (value.length() > 100) {
                    value = value.substring(0, 97) + "...";
                }

                sb.append(String.format("%-30s = %s\n", key, value));
            }
        }

        return sb.toString();
    }

    private String setProperty(String key, String value) {
        try {
            String oldValue = System.getProperty(key);
            System.setProperty(key, value);

            String maskedOld = SecurityValidator.maskSensitiveValue(key, oldValue);
            String maskedNew = SecurityValidator.maskSensitiveValue(key, value);

            StringBuilder sb = new StringBuilder();
            sb.append("=== System Property Updated ===\n");
            sb.append("Key: ").append(key).append("\n");
            sb.append("Old Value: ").append(oldValue != null ? maskedOld : "<not set>").append("\n");
            sb.append("New Value: ").append(maskedNew).append("\n");

            // Verify the change
            String verifyValue = System.getProperty(key);
            if (value.equals(verifyValue)) {
                sb.append("Status: Successfully updated\n");
            } else {
                sb.append("Status: WARNING - Property may not have been updated as expected\n");
            }

            return sb.toString();
        } catch (SecurityException e) {
            return String.format("Failed to set system property '%s': %s", key, e.getMessage());
        }
    }

    private boolean isValidPropertyKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        // Typical Java system property key is dot-separated; keep it strict to reduce abuse surface.
        return key.matches("^[a-zA-Z0-9_.-]{1,200}$");
    }

    private boolean isValidPropertyValue(String value) {
        if (value == null) {
            return false;
        }
        if (value.length() > 1000) {
            return false;
        }
        // Reuse the project's masking heuristics and reject obvious log/command injection characters.
        String sanitized = SecurityValidator.sanitizeInput(value);
        return value.equals(sanitized);
    }

    private String getHelpText() {
        return "=== System Property Command Help ===\n" +
               "View and modify system properties\n\n" +
               "Usage:\n" +
               "  sysprop                    List all system properties\n" +
               "  sysprop <key>              Get specific property value\n" +
               "  sysprop <pattern>          Search properties using wildcards (* and ?)\n" +
               "  sysprop set <key> <value>  Set property to new value\n" +
               "  sysprop --help             Show this help message\n\n" +
               "Examples:\n" +
               "  sysprop java.version       Get Java version\n" +
               "  sysprop java.*             Find all Java-related properties\n" +
               "  sysprop set user.timezone GMT  Set timezone to GMT\n" +
               "  sysprop *path*             Find all path-related properties\n\n" +
               "Note: Some system properties may be read-only depending on the JVM\n" +
               "and security manager configuration.\n";
    }

    @Override
    public String getDescription() {
        return "View and modify system properties";
    }
}
