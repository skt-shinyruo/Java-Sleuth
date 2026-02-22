package com.javasleuth.foundation.security;

import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;

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
        "redefine", "retransform", "heapdump", "mc", "reset", "stop", "vmtool"
    ));

    private final ConfigView config;
    private final AuditLogger auditLogger;

    /**
     * 构造注入路径（推荐）。
     *
     * <p>注意：该构造器不再对 null 依赖做隐式单例回退，以避免不透明的依赖来源。</p>
     */
    public InputValidator(ConfigView config, AuditLogger auditLogger) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (auditLogger == null) {
            throw new IllegalArgumentException("auditLogger is required");
        }
        this.config = config;
        this.auditLogger = auditLogger;
    }

    /**
     * 默认装配（显式列出依赖来源，避免构造器内部隐式 getInstance 回退）。
     */
    public static InputValidator createDefault() {
        return new InputValidator(ProductionConfig.getInstance(), AuditLogger.getInstance());
    }

    public ValidationResult validateCommand(String sessionId, String clientInfo, String command, String[] args) {
        if (!SleuthConfigSchema.SECURITY_INPUT_VALIDATION.read(config)) {
            return ValidationResult.valid();
        }

        // Basic null/empty checks
        if (command == null || command.trim().isEmpty()) {
            auditLogger.logInputValidationFailure(sessionId, clientInfo, command, "Empty command");
            return ValidationResult.invalid("Command cannot be empty");
        }

        // Length validation
        int maxCommandLength = SleuthConfigSchema.SECURITY_MAX_COMMAND_LENGTH.read(config);
        if (command.length() > maxCommandLength) {
            auditLogger.logInputValidationFailure(sessionId, clientInfo, command, "Command too long");
            return ValidationResult.invalid("Command exceeds maximum length: " + maxCommandLength);
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
            case "mc":
                // mc <source-file-path> [options]
                if (position == 1) {
                    if (arg.contains("..") || !arg.endsWith(".java")) {
                        auditLogger.logInputValidationFailure(sessionId, clientInfo, arg, "Invalid source file path");
                        return ValidationResult.invalid("Invalid source file path for mc (expected .java)");
                    }
                }
                break;

            case "redefine":
                // redefine <class-name> <class-file-path> [options]
                if (position == 1) {
                    if (!arg.matches("^[a-zA-Z0-9_.$]+$") || !SecurityValidator.isClassAccessible(arg)) {
                        auditLogger.logInputValidationFailure(sessionId, clientInfo, arg, "Invalid class name");
                        return ValidationResult.invalid("Invalid class name for redefine");
                    }
                } else if (position == 2) {
                    if (arg.contains("..") || !arg.endsWith(".class")) {
                        auditLogger.logInputValidationFailure(sessionId, clientInfo, arg, "Invalid class file path");
                        return ValidationResult.invalid("Invalid class file path for redefine (expected .class)");
                    }
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
                // heapdump [options] [filename]
                String file = null;
                if (arg.startsWith("--file=")) {
                    file = arg.substring("--file=".length());
                } else if (!arg.startsWith("-")) {
                    file = arg;
                }
                if (file != null) {
                    String normalized = file.toLowerCase();
                    if (file.contains("..") || normalized.contains("/etc/") || normalized.contains("/proc/") || normalized.contains("/sys/")) {
                        auditLogger.logSecurityViolation(sessionId, clientInfo, "HEAP_DUMP_PATH",
                            "Suspicious heap dump path: " + file);
                        return ValidationResult.invalid("Invalid heap dump path");
                    }
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
            "Privileged command attempted: " + command + ", client=" + clientInfo);

        // For demo purposes, we'll allow all privileged commands
        // In production, you might want to add role-based access control
        return ValidationResult.valid();
    }

    private boolean isCommandAllowed(String command) {
        String allowedCommands = SleuthConfigSchema.SECURITY_ALLOWED_COMMANDS.read(config);
        if (allowedCommands == null) {
            return false;
        }
        String raw = allowedCommands.trim();
        if ("*".equals(raw)) {
            return true;
        }

        Set<String> allowed = new HashSet<>();
        for (String token : raw.split(",")) {
            if (token == null) {
                continue;
            }
            String v = token.trim().toLowerCase();
            if (v.isEmpty()) {
                continue;
            }
            if ("*".equals(v)) {
                return true;
            }
            allowed.add(v);
        }
        return allowed.contains(command.toLowerCase());
    }

    public ValidationResult sanitizeOutput(String output) {
        if (!SleuthConfigSchema.SECURITY_INPUT_VALIDATION.read(config)) {
            return ValidationResult.valid(output);
        }

        if (output == null) {
            return ValidationResult.valid("");
        }

        // Remove potential log injection characters
        String sanitized = output.replaceAll("[\r\n\t\\p{Cntrl}&&[^\r\n\t]]", "");

        // Truncate if too long
        if (sanitized.length() > 10000) {
            sanitized = sanitized.substring(0, 10000) + " ... [Output truncated for security]";
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
