package com.javasleuth.foundation.command.protocol;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A small utility for parsing/encoding simple whitespace separated {@code k=v} protocol lines.
 *
 * <p>Expected format:</p>
 *
 * <pre>
 * VERB k=v k=v ...
 * </pre>
 *
 * <p>Notes/constraints (kept intentionally minimal to match existing protocol usage):</p>
 *
 * <ul>
 *   <li>The first token (verb) is ignored by {@link #parseAfterVerb(String)}.</li>
 *   <li>Tokens are split by whitespace ({@code \\s+}). Values cannot contain spaces.</li>
 *   <li>Keys are normalized to lowercase to keep the protocol case-insensitive.</li>
 *   <li>Invalid tokens are ignored.</li>
 * </ul>
 */
public final class KvLineCodec {

    private KvLineCodec() {
    }

    /**
     * Parses a protocol line and returns {@code k -> v} pairs from tokens after the leading verb.
     *
     * <p>Example:</p>
     *
     * <pre>
     * CONFIG v=1 protocol=binary connId=abc
     * </pre>
     */
    public static Map<String, String> parseAfterVerb(String line) {
        Map<String, String> kv = new HashMap<>();
        if (line == null) {
            return kv;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return kv;
        }

        String[] tokens = trimmed.split("\\s+");
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            int idx = token.indexOf('=');
            if (idx <= 0 || idx >= token.length() - 1) {
                continue;
            }
            String key = token.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = token.substring(idx + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                kv.put(key, value);
            }
        }
        return kv;
    }

    /**
     * Encodes a verb + kv pairs line. This method does not implement escaping/quoting; callers must
     * ensure values do not contain spaces.
     */
    public static String encode(String verb, Map<String, String> kv) {
        if (verb == null || verb.trim().isEmpty()) {
            throw new IllegalArgumentException("verb is required");
        }
        StringBuilder out = new StringBuilder();
        out.append(verb.trim());
        if (kv == null || kv.isEmpty()) {
            return out.toString();
        }
        for (Map.Entry<String, String> entry : kv.entrySet()) {
            if (entry == null) {
                continue;
            }
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            String k = key.trim();
            String v = value.trim();
            if (k.isEmpty() || v.isEmpty()) {
                continue;
            }
            out.append(' ').append(k).append('=').append(v);
        }
        return out.toString();
    }
}
