package com.javasleuth.command;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Maps internal exceptions to short, user-facing error messages.
 *
 * <p>Design goals:</p>
 * <ul>
 *   <li>No stack traces in user output.</li>
 *   <li>Minimal disclosure with best-effort redaction of sensitive tokens.</li>
 *   <li>Provide a stable correlation field (errorId) to find diagnostics in logs.</li>
 * </ul>
 */
public final class CommandErrorMapper {
    private static final SecureRandom ERROR_ID_RANDOM = new SecureRandom();

    private CommandErrorMapper() {}

    public static String newErrorId() {
        byte[] bytes = new byte[9];
        ERROR_ID_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String toUserMessage(Throwable t, String errorId, CommandContext context) {
        String base = mapBaseMessage(t);
        String suffix = formatCorrelationSuffix(errorId, context);
        return base + suffix;
    }

    public static String toUserMessage(Throwable t, String errorId) {
        return toUserMessage(t, errorId, null);
    }

    private static String mapBaseMessage(Throwable t) {
        if (t == null) {
            return "Command execution failed";
        }

        // Prefer a short, sanitized exception message when available.
        String message = t.getMessage();
        if (t instanceof IllegalArgumentException) {
            String safe = sanitizeAndRedact(message);
            return safe.isEmpty() ? "Invalid command arguments" : safe;
        }
        if (t instanceof SecurityException) {
            String safe = sanitizeAndRedact(message);
            return safe.isEmpty() ? "Permission denied" : safe;
        }

        String safe = sanitizeAndRedact(message);
        return safe.isEmpty() ? "Command execution failed" : safe;
    }

    private static String formatCorrelationSuffix(String errorId, CommandContext context) {
        String id = errorId != null ? errorId.trim() : "";
        if (id.isEmpty() && context == null) {
            return "";
        }

        String connId = null;
        if (context != null) {
            connId = context.getConnId();
        }
        String conn = connId != null ? connId.trim() : "";

        StringBuilder sb = new StringBuilder(32);
        if (!id.isEmpty()) {
            sb.append("errorId=").append(id);
        }
        if (!conn.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("connId=").append(conn);
        }
        if (sb.length() == 0) {
            return "";
        }
        return " (" + sb + ")";
    }

    private static String sanitizeAndRedact(String raw) {
        if (raw == null) {
            return "";
        }

        String sanitized = sanitize(raw);
        if (sanitized.isEmpty()) {
            return "";
        }

        // Keep user messages short and stable.
        sanitized = truncate(sanitized, 200);

        // Best-effort redaction: mask explicit key=value patterns and long token-like strings.
        String redacted = redactKeyValueSecrets(sanitized);
        redacted = redactLongTokens(redacted);
        return redacted.trim();
    }

    private static String sanitize(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (maxLen <= 0) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
    }

    private static String redactKeyValueSecrets(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        // Avoid regex overhead in hot paths; implement a small best-effort scanner.
        // Supported patterns: "key=value" and "key: value" where key contains sensitive keywords.
        String lower = value.toLowerCase();
        String[] keys = new String[] { "password", "secret", "token", "credential", "session", "apikey", "api_key" };

        int idx = 0;
        StringBuilder out = new StringBuilder(value.length());
        while (idx < value.length()) {
            int next = -1;
            String matchedKey = null;
            for (String k : keys) {
                int p = lower.indexOf(k, idx);
                if (p >= 0 && (next < 0 || p < next)) {
                    next = p;
                    matchedKey = k;
                }
            }
            if (next < 0) {
                out.append(value.substring(idx));
                break;
            }

            out.append(value, idx, next + matchedKey.length());
            idx = next + matchedKey.length();

            // Skip whitespace.
            while (idx < value.length() && Character.isWhitespace(value.charAt(idx))) {
                out.append(value.charAt(idx));
                idx++;
            }
            if (idx >= value.length()) {
                break;
            }

            char sep = value.charAt(idx);
            if (sep != '=' && sep != ':') {
                continue;
            }

            // Write separator and skip whitespace after it.
            out.append(sep);
            idx++;
            while (idx < value.length() && Character.isWhitespace(value.charAt(idx))) {
                out.append(value.charAt(idx));
                idx++;
            }

            // Mask the value until we hit a delimiter.
            out.append("***");
            while (idx < value.length()) {
                char c = value.charAt(idx);
                if (Character.isWhitespace(c) || c == ',' || c == ';' || c == ')' || c == ']' || c == '}') {
                    break;
                }
                idx++;
            }
        }

        return out.toString();
    }

    private static String redactLongTokens(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (isTokenChar(c)) {
                int start = i;
                int j = i + 1;
                while (j < value.length() && isTokenChar(value.charAt(j))) {
                    j++;
                }
                int len = j - start;
                if (len >= 20) {
                    out.append(value, start, start + 4).append("****").append(value, j - 4, j);
                } else {
                    out.append(value, start, j);
                }
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9')
            || c == '-' || c == '_' || c == '.';
    }
}

