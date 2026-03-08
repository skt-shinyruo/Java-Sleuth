package com.javasleuth.launcher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper utilities for launcher-side restart (stop + re-attach).
 *
 * <p>Kept small and dependency-free so it can be unit-tested without requiring a running agent.</p>
 */
final class RestartSupport {
    private RestartSupport() {}

    private static final Pattern CONFIRM_TOKEN_PATTERN = Pattern.compile("--confirm\\s+([A-Za-z0-9_-]{6,})");

    static boolean looksLikeAuthIssue(String stderr) {
        if (stderr == null) {
            return false;
        }
        String s = stderr.toLowerCase();
        return s.contains("authentication required")
            || s.contains("invalid or expired session")
            || s.contains("insufficient permissions")
            || s.contains("required: admin");
    }

    static String extractConfirmTokenBestEffort(String stderr) {
        if (stderr == null || stderr.trim().isEmpty()) {
            return null;
        }
        Matcher m = CONFIRM_TOKEN_PATTERN.matcher(stderr);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last != null && !last.trim().isEmpty() ? last.trim() : null;
    }
}

