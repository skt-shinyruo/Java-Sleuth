package com.javasleuth.security;

import java.io.File;
import java.security.Permission;
import java.util.regex.Pattern;

public class SecurityValidator {
    // Allow common file path characters on Linux/macOS/Windows (avoid control chars and obvious injection delimiters).
    private static final Pattern SAFE_PATH_PATTERN = Pattern.compile("^[a-zA-Z0-9._/\\\\:\\- ]+$");
    private static final String[] SENSITIVE_PROPERTIES = {
        "password", "secret", "key", "token", "credential"
    };

    public static boolean isValidPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }

        // Check for directory traversal attempts
        if (path.contains("..") || path.contains("//")) {
            return false;
        }

        // Check for potentially dangerous paths
        String normalizedPath = path.toLowerCase();
        if (normalizedPath.startsWith("/etc/") ||
            normalizedPath.startsWith("/proc/") ||
            normalizedPath.startsWith("/sys/") ||
            normalizedPath.contains("/passwd") ||
            normalizedPath.contains("/shadow")) {
            return false;
        }

        return SAFE_PATH_PATTERN.matcher(path).matches();
    }

    public static boolean canAccessFile(String filePath) {
        // Backward-compatible semantics: "can read existing file, or can write to parent for new file".
        return canReadFile(filePath) || canWriteFile(filePath);
    }

    public static boolean canReadFile(String filePath) {
        if (!isValidPath(filePath)) {
            return false;
        }
        try {
            File file = new File(filePath);
            return file.exists() && file.isFile() && file.canRead();
        } catch (SecurityException e) {
            return false;
        }
    }

    public static boolean canWriteFile(String filePath) {
        if (!isValidPath(filePath)) {
            return false;
        }

        try {
            File file = new File(filePath);
            if (file.exists()) {
                return file.isFile() && file.canWrite();
            }

            // New file: treat null parent as current working directory.
            File parent = file.getParentFile();
            if (parent == null) {
                parent = new File(".");
            }
            return parent.exists() && parent.isDirectory() && parent.canWrite();
        } catch (SecurityException e) {
            return false;
        }
    }

    public static String sanitizeInput(String input) {
        if (input == null) {
            return "";
        }

        // Remove potentially dangerous characters
        return input.replaceAll("[<>\"'&;]", "")
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    public static String maskSensitiveValue(String key, String value) {
        if (key == null || value == null) {
            return value;
        }

        String lowerKey = key.toLowerCase();
        for (String sensitive : SENSITIVE_PROPERTIES) {
            if (lowerKey.contains(sensitive)) {
                if (value.length() <= 4) {
                    return "****";
                } else {
                    return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
                }
            }
        }
        return value;
    }

    public static boolean isPermissionGranted(String operation) {
        try {
            SecurityManager sm = System.getSecurityManager();
            if (sm == null) {
                return true; // No security manager, allow operation
            }

            // Check specific permissions based on operation
            switch (operation.toLowerCase()) {
                case "heapdump":
                case "gc":
                    sm.checkPermission(new RuntimePermission("modifyRuntimeState"));
                    break;
                case "sysprop":
                    sm.checkPermission(new RuntimePermission("getSystemProperties"));
                    break;
                case "redefine":
                case "retransform":
                    sm.checkPermission(new RuntimePermission("redefineClasses"));
                    break;
                case "file_read":
                    sm.checkPermission(new RuntimePermission("readFileDescriptor"));
                    break;
                case "file_write":
                    sm.checkPermission(new RuntimePermission("writeFileDescriptor"));
                    break;
                default:
                    return true; // Allow other operations by default
            }
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    public static String getSecurityContext() {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null) {
            return "No SecurityManager installed";
        }

        return "SecurityManager: " + sm.getClass().getName();
    }

    public static boolean isClassAccessible(String className) {
        if (className == null || className.trim().isEmpty()) {
            return false;
        }

        // Block access to security-sensitive classes
        if (className.startsWith("java.security.") ||
            className.startsWith("sun.security.") ||
            className.startsWith("com.sun.crypto.") ||
            className.contains("Password") ||
            className.contains("Credential")) {
            return false;
        }

        return true;
    }
}
