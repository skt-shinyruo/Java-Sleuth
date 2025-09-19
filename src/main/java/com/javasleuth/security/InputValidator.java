package com.javasleuth.security;

import com.javasleuth.config.ProductionConfig;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class InputValidator {
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final Pattern SAFE_ARGUMENT_PATTERN = Pattern.compile("^[a-zA-Z0-9_.,/:-]+$");

    // Dangerous patterns that should be blocked
    private static final Pattern[] DANGEROUS_PATTERNS = {
        Pattern.compile(".*\\.\\..*"), // Path traversal
        Pattern.compile(".*[<>\"'].*"), // HTML/XML injection
        Pattern.compile(".*\\$\\{.*\\}.*"), // Variable substitution
        Pattern.compile(".*\\bldap://.*", Pattern.CASE_INSENSITIVE), // LDAP injection
        Pattern.compile(".*\\bjndi:.*", Pattern.CASE_INSENSITIVE), // JNDI injection
        Pattern.compile(".*\\bfile:.*", Pattern.CASE_INSENSITIVE), // File protocol
        Pattern.compile(".*\\bjar:.*", Pattern.CASE_INSENSITIVE), // JAR protocol
        Pattern.compile(".*[\\r\\n].*"), // Command injection via newlines
        Pattern.compile(".*;.*"), // Command chaining
        Pattern.compile(".*\\|.*"), // Pipe operations
        Pattern.compile(".*&.*"), // Background execution
    };

    // Commands that require special permission validation
    private static final Set<String> PRIVILEGED_COMMANDS = new HashSet<>(Arrays.asList(
        "redefine", "retransform", "heapdump", "mc"
    ));

    private final ProductionConfig config;
    private final AuditLogger auditLogger;

    public InputValidator() {
        this.config = ProductionConfig.getInstance();
        this.auditLogger = AuditLogger.getInstance();
    }

    public ValidationResult validateCommand(String sessionId, String clientInfo, String command, String[] args) {
        if (!config.isInputValidationEnabled()) {
            return ValidationResult.valid();
        }

        // Basic null/empty checks
        if (command == null || command.trim().isEmpty()) {
            auditLogger.logInputValidationFailure(sessionId, clientInfo, command, "Empty command");
            return ValidationResult.invalid("Command cannot be empty");
        }

        // Length validation
        if (command.length() > config.getMaxCommandLength()) {
            auditLogger.logInputValidationFailure(sessionId, clientInfo, command, "Command too long");
            return ValidationResult.invalid("Command exceeds maximum length: " + config.getMaxCommandLength());
        }

        // Command format validation
        if (!COMMAND_PATTERN.matcher(command).matches()) {
            auditLogger.logInputValidationFailure(sessionId, clientInfo, command, "Invalid command format");
            return ValidationResult.invalid("Invalid command format. Only alphanumeric characters, underscores, and hyphens allowed.");
        }

        // Check against allowed commands
        if (!isCommandAllowed(command)) {
            auditLogger.logAuthorizationFailure(sessionId, clientInfo, command, "Command not in allowed list");
            return ValidationResult.invalid("Command not permitted: " + command);
        }

        // Validate arguments
        if (args != null) {
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                ValidationResult argResult = validateArgument(sessionId, clientInfo, command, arg, i);
                if (!argResult.isValid()) {
                    return argResult;
                }
            }
        }

        // Additional validation for privileged commands
        if (PRIVILEGED_COMMANDS.contains(command.toLowerCase())) {
            ValidationResult privResult = validatePrivilegedCommand(sessionId, clientInfo, command, args);
            if (!privResult.isValid()) {
                return privResult;
            }
        }

        return ValidationResult.valid();
    }

    private ValidationResult validateArgument(String sessionId, String clientInfo, String command, String arg, int position) {
        if (arg == null) {
            return ValidationResult.valid(); // Null args are okay
        }

        // Length check
        if (arg.length() > 1000) {
            auditLogger.logInputValidationFailure(sessionId, clientInfo, arg, "Argument too long");
            return ValidationResult.invalid("Argument " + position + " exceeds maximum length");
        }

        // Check for dangerous patterns
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(arg).matches()) {
                auditLogger.logSecurityViolation(sessionId, clientInfo, "DANGEROUS_PATTERN",
                    "Argument contains dangerous pattern: " + arg);
                return ValidationResult.invalid("Argument contains prohibited patterns");
            }
        }

        // Command-specific argument validation
        return validateCommandSpecificArguments(sessionId, clientInfo, command, arg, position);
    }

    private ValidationResult validateCommandSpecificArguments(String sessionId, String clientInfo,
                                                            String command, String arg, int position) {
        switch (command.toLowerCase()) {
            case "redefine":
            case "mc":
                // File path arguments need special validation
                if (position == 1 && (arg.contains("..") || !arg.endsWith(".java"))) {
                    auditLogger.logInputValidationFailure(sessionId, clientInfo, arg, "Invalid file path");
                    return ValidationResult.invalid("Invalid file path for " + command);
                }
                break;

            case "watch":
            case "trace":
                // Class and method names should be safe
                if (position == 1 && !arg.matches("^[a-zA-Z0-9_.$*]+$")) {
                    auditLogger.logInputValidationFailure(sessionId, clientInfo, arg, "Invalid class pattern");
                    return ValidationResult.invalid("Invalid class pattern");
                }
                break;

            case "heapdump":
                // Heap dump path validation
                if (position == 1 && (arg.contains("..") || arg.contains("/etc/") || arg.contains("/proc/"))) {
                    auditLogger.logSecurityViolation(sessionId, clientInfo, "HEAP_DUMP_PATH",
                        "Suspicious heap dump path: " + arg);
                    return ValidationResult.invalid("Invalid heap dump path");
                }
                break;

            case "sc":
            case "sm":
                // Search patterns should be safe
                if (position == 1 && arg.length() > 200) {
                    auditLogger.logInputValidationFailure(sessionId, clientInfo, arg, "Search pattern too long");
                    return ValidationResult.invalid("Search pattern too long");
                }
                break;
        }

        return ValidationResult.valid();
    }

    private ValidationResult validatePrivilegedCommand(String sessionId, String clientInfo, String command, String[] args) {
        // Additional security checks for privileged commands
        auditLogger.logSystemEvent("PRIVILEGED_COMMAND_ACCESS",
            "Session " + sessionId + " attempting privileged command: " + command);

        // For demo purposes, we'll allow all privileged commands
        // In production, you might want to add role-based access control
        return ValidationResult.valid();
    }

    private boolean isCommandAllowed(String command) {
        String allowedCommands = config.getAllowedCommands();
        if ("*".equals(allowedCommands)) {
            return true;
        }

        Set<String> allowed = new HashSet<>(Arrays.asList(allowedCommands.split(",")));
        return allowed.contains(command.toLowerCase());
    }

    public ValidationResult sanitizeOutput(String output) {
        if (!config.isInputValidationEnabled()) {
            return ValidationResult.valid(output);
        }

        if (output == null) {
            return ValidationResult.valid("");
        }

        // Remove potential log injection characters
        String sanitized = output.replaceAll("[\r\n\t\\p{Cntrl}&&[^\r\n\t]]", "");

        // Truncate if too long
        if (sanitized.length() > 10000) {
            sanitized = sanitized.substring(0, 10000) + "\n... [Output truncated for security]";
        }

        return ValidationResult.valid(sanitized);
    }

    // Validation result class
    public static class ValidationResult {
        private final boolean valid;
        private final String message;
        private final String sanitizedOutput;

        private ValidationResult(boolean valid, String message, String sanitizedOutput) {
            this.valid = valid;
            this.message = message;
            this.sanitizedOutput = sanitizedOutput;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null, null);
        }

        public static ValidationResult valid(String sanitizedOutput) {
            return new ValidationResult(true, null, sanitizedOutput);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message, null);
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }

        public String getSanitizedOutput() {
            return sanitizedOutput;
        }
    }
}