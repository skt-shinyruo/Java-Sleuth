package com.javasleuth.util;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Safe-ish formatter for diagnostic outputs.
 *
 * <p>Goals:
 * - Avoid deep reflection (performance + side effects)
 * - Limit depth/length/items
 * - Mask common sensitive keys
 */
public final class SleuthValueFormatter {
    private SleuthValueFormatter() {}

    public static final class Options {
        private int maxDepth = 2;
        private int maxStringLength = 200;
        private int maxCollectionItems = 20;
        private int maxMapEntries = 20;

        public int getMaxDepth() { return maxDepth; }
        public int getMaxStringLength() { return maxStringLength; }
        public int getMaxCollectionItems() { return maxCollectionItems; }
        public int getMaxMapEntries() { return maxMapEntries; }

        public Options withMaxDepth(int maxDepth) { this.maxDepth = Math.max(0, maxDepth); return this; }
        public Options withMaxStringLength(int maxStringLength) { this.maxStringLength = Math.max(0, maxStringLength); return this; }
        public Options withMaxCollectionItems(int maxCollectionItems) { this.maxCollectionItems = Math.max(0, maxCollectionItems); return this; }
        public Options withMaxMapEntries(int maxMapEntries) { this.maxMapEntries = Math.max(0, maxMapEntries); return this; }
    }

    public static String format(Object value) {
        return format(value, new Options());
    }

    public static String format(Object value, Options options) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        return formatInternal(value, options, options.getMaxDepth(), seen);
    }

    public static String formatThrowable(Throwable t, Options options) {
        if (t == null) {
            return "null";
        }
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getName();
        }
        return t.getClass().getName() + ": " + truncate(msg, options.getMaxStringLength());
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return k.contains("password") || k.contains("passwd") || k.contains("secret") || k.contains("token") || k.contains("key")
            || k.contains("credential") || k.contains("auth") || k.contains("session");
    }

    private static String formatInternal(Object value, Options options, int depthLeft, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) {
            return "null";
        }

        if (value instanceof CharSequence) {
            return "\"" + truncate(value.toString(), options.getMaxStringLength()) + "\"";
        }

        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }

        if (value instanceof Class) {
            return ((Class<?>) value).getName();
        }

        if (value instanceof Throwable) {
            return formatThrowable((Throwable) value, options);
        }

        if (depthLeft <= 0) {
            return summarize(value);
        }

        if (seen.put(value, Boolean.TRUE) != null) {
            return summarize(value) + "(cycle)";
        }

        try {
            Class<?> c = value.getClass();
            if (c.isArray()) {
                return formatArray(value, options, depthLeft - 1, seen);
            }
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> m = (Map<Object, Object>) value;
                return formatMap(m, options, depthLeft - 1, seen);
            }
            if (value instanceof Collection) {
                return formatCollection((Collection<?>) value, options, depthLeft - 1, seen);
            }
            if (value instanceof Iterable) {
                return formatIterable((Iterable<?>) value, options, depthLeft - 1, seen);
            }

            return summarize(value);
        } finally {
            seen.remove(value);
        }
    }

    private static String formatArray(Object array, Options options, int depthLeft, IdentityHashMap<Object, Boolean> seen) {
        int len = Array.getLength(array);
        int n = Math.min(len, options.getMaxCollectionItems());
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object v = Array.get(array, i);
            sb.append(formatInternal(v, options, depthLeft, seen));
        }
        if (len > n) {
            sb.append(", ... +").append(len - n);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatCollection(Collection<?> c, Options options, int depthLeft, IdentityHashMap<Object, Boolean> seen) {
        int n = Math.min(c.size(), options.getMaxCollectionItems());
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        int i = 0;
        for (Object v : c) {
            if (i >= n) {
                break;
            }
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(formatInternal(v, options, depthLeft, seen));
            i++;
        }
        if (c.size() > n) {
            sb.append(", ... +").append(c.size() - n);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatIterable(Iterable<?> it, Options options, int depthLeft, IdentityHashMap<Object, Boolean> seen) {
        Iterator<?> iter = it.iterator();
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        int i = 0;
        while (iter.hasNext() && i < options.getMaxCollectionItems()) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(formatInternal(iter.next(), options, depthLeft, seen));
            i++;
        }
        if (iter.hasNext()) {
            sb.append(", ...");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatMap(Map<Object, Object> map, Options options, int depthLeft, IdentityHashMap<Object, Boolean> seen) {
        int n = Math.min(map.size(), options.getMaxMapEntries());
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        int i = 0;
        for (Map.Entry<Object, Object> e : map.entrySet()) {
            if (i >= n) {
                break;
            }
            if (i > 0) {
                sb.append(", ");
            }
            String k = String.valueOf(e.getKey());
            sb.append(k).append("=");
            if (isSensitiveKey(k)) {
                sb.append("\"****\"");
            } else {
                sb.append(formatInternal(e.getValue(), options, depthLeft, seen));
            }
            i++;
        }
        if (map.size() > n) {
            sb.append(", ... +").append(map.size() - n);
        }
        sb.append("}");
        return sb.toString();
    }

    private static String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        return value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        if (maxLen <= 0) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }
}

