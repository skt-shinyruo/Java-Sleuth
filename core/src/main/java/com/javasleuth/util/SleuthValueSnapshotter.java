package com.javasleuth.util;

public final class SleuthValueSnapshotter {
    private SleuthValueSnapshotter() {}

    public static Object[] snapshotParameters(Object[] parameters, SleuthValueFormatter.Options options) {
        if (parameters == null) {
            return null;
        }
        Object[] out = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            out[i] = snapshotValue(parameters[i], options);
        }
        return out;
    }

    public static Object snapshotValue(Object value, SleuthValueFormatter.Options options) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence) {
            return truncate(value.toString(), options != null ? options.getMaxStringLength() : 200);
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        if (value.getClass().isEnum()) {
            Enum<?> e = (Enum<?>) value;
            return new SleuthSnapshotValue(e.getDeclaringClass().getName() + "." + e.name());
        }
        String formatted = SleuthValueFormatter.format(value, options != null ? options : new SleuthValueFormatter.Options());
        return new SleuthSnapshotValue(formatted);
    }

    public static Object snapshotThrowable(Throwable t, SleuthValueFormatter.Options options) {
        if (t == null) {
            return null;
        }
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return new SleuthSnapshotValue(t.getClass().getName());
        }
        int max = options != null ? options.getMaxStringLength() : 200;
        return new SleuthSnapshotValue(t.getClass().getName() + ": " + truncate(msg, max));
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
