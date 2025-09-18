package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

public class SysEnvCommand implements Command {
    private final Instrumentation instrumentation;

    public SysEnvCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length > 1 && "--help".equals(args[1])) {
            return getHelpText();
        }

        if (args.length == 1) {
            // List all environment variables
            return listAllEnvironmentVariables();
        } else if (args.length == 2) {
            // Get specific environment variable or pattern search
            String key = args[1];
            if (key.contains("*")) {
                return searchEnvironmentVariables(key);
            } else {
                return getEnvironmentVariable(key);
            }
        } else {
            return "Invalid arguments. Use: sysenv [key] or sysenv --help for help";
        }
    }

    private String listAllEnvironmentVariables() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== System Environment Variables ===\n");

        Map<String, String> env = System.getenv();
        Map<String, String> sortedEnv = new TreeMap<>(env);

        sb.append(String.format("Total environment variables: %d\n\n", sortedEnv.size()));

        for (Map.Entry<String, String> entry : sortedEnv.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // Truncate long values for readability and mask sensitive information
            if (value.length() > 100) {
                value = value.substring(0, 97) + "...";
            }

            // Mask potentially sensitive environment variables
            if (isSensitiveVariable(key)) {
                value = maskSensitiveValue(value);
            }

            sb.append(String.format("%-30s = %s\n", key, value));
        }

        return sb.toString();
    }

    private String getEnvironmentVariable(String key) {
        String value = System.getenv(key);
        if (value == null) {
            return String.format("Environment variable '%s' not found", key);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Environment Variable ===\n");
            sb.append("Key: ").append(key).append("\n");

            if (isSensitiveVariable(key)) {
                sb.append("Value: ").append(maskSensitiveValue(value)).append("\n");
                sb.append("Note: Value masked for security (contains sensitive information)\n");
            } else {
                sb.append("Value: ").append(value).append("\n");
            }

            return sb.toString();
        }
    }

    private String searchEnvironmentVariables(String pattern) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Environment Variables Search ===\n");
        sb.append("Pattern: ").append(pattern).append("\n\n");

        // Convert wildcard pattern to regex
        String regex = pattern.replace("*", ".*").replace("?", ".?");
        Pattern compiledPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);

        Map<String, String> env = System.getenv();
        Map<String, String> matchingEnv = new TreeMap<>();

        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            if (compiledPattern.matcher(key).matches()) {
                matchingEnv.put(key, entry.getValue());
            }
        }

        if (matchingEnv.isEmpty()) {
            sb.append("No environment variables found matching pattern: ").append(pattern);
        } else {
            sb.append(String.format("Found %d matching environment variables:\n\n", matchingEnv.size()));

            for (Map.Entry<String, String> entry : matchingEnv.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                // Truncate long values for readability and mask sensitive information
                if (value.length() > 100) {
                    value = value.substring(0, 97) + "...";
                }

                if (isSensitiveVariable(key)) {
                    value = maskSensitiveValue(value);
                }

                sb.append(String.format("%-30s = %s\n", key, value));
            }
        }

        return sb.toString();
    }

    private boolean isSensitiveVariable(String key) {
        if (key == null) return false;
        String lowerKey = key.toLowerCase();

        return lowerKey.contains("password") ||
               lowerKey.contains("secret") ||
               lowerKey.contains("key") ||
               lowerKey.contains("token") ||
               lowerKey.contains("credential") ||
               lowerKey.contains("auth") ||
               lowerKey.contains("api_key") ||
               lowerKey.contains("private") ||
               lowerKey.contains("cert") ||
               lowerKey.contains("ssh");
    }

    private String maskSensitiveValue(String value) {
        if (value == null || value.length() <= 4) {
            return "***";
        }

        int visibleChars = Math.min(4, value.length() / 4);
        String visible = value.substring(0, visibleChars);
        return visible + "***" + (value.length() > visibleChars + 3 ? "(" + (value.length() - visibleChars) + " chars)" : "");
    }

    private String getHelpText() {
        return "=== System Environment Command Help ===\n" +
               "Inspect system environment variables\n\n" +
               "Usage:\n" +
               "  sysenv                     List all environment variables\n" +
               "  sysenv <key>               Get specific environment variable\n" +
               "  sysenv <pattern>           Search environment variables using wildcards (* and ?)\n" +
               "  sysenv --help              Show this help message\n\n" +
               "Examples:\n" +
               "  sysenv PATH                Get PATH environment variable\n" +
               "  sysenv JAVA_*              Find all Java-related environment variables\n" +
               "  sysenv *HOME*              Find all variables containing 'HOME'\n" +
               "  sysenv USER                Get current user\n\n" +
               "Security Features:\n" +
               "- Sensitive variables (passwords, keys, tokens, etc.) are automatically masked\n" +
               "- Long values are truncated for better readability\n" +
               "- Case-insensitive pattern matching\n\n" +
               "Note: Environment variables are read-only from the Java process perspective.\n";
    }

    @Override
    public String getDescription() {
        return "Inspect system environment variables";
    }
}