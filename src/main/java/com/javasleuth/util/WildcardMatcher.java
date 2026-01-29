package com.javasleuth.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Wildcard matcher that treats '*' as "match any substring" and escapes all other regex meta chars.
 *
 * <p>This is intentionally conservative to avoid letting user input become arbitrary regex.
 */
public final class WildcardMatcher {
    private WildcardMatcher() {}

    public static boolean matches(String text, String wildcardPattern) {
        if (text == null || wildcardPattern == null) {
            return false;
        }
        if ("*".equals(wildcardPattern)) {
            return true;
        }
        if (!wildcardPattern.contains("*")) {
            return text.equals(wildcardPattern);
        }
        return compile(wildcardPattern).matcher(text).matches();
    }

    public static Pattern compile(String wildcardPattern) {
        if (wildcardPattern == null) {
            return Pattern.compile("^$");
        }
        if ("*".equals(wildcardPattern)) {
            return Pattern.compile(".*");
        }

        List<String> parts = splitByAsterisk(wildcardPattern);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(parts.get(i)));
        }
        return Pattern.compile(regex.toString());
    }

    private static List<String> splitByAsterisk(String pattern) {
        List<String> parts = new ArrayList<>();
        int last = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '*') {
                parts.add(pattern.substring(last, i));
                last = i + 1;
            }
        }
        parts.add(pattern.substring(last));
        return parts;
    }
}

