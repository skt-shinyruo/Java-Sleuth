package com.javasleuth.core.command;

import java.util.ArrayList;
import java.util.List;

/**
 * 命令参数相关的小工具类。
 *
 * <p>注意：该工具类不做业务判断，仅提供参数规范化能力。</p>
 */
public final class CommandArgs {
    public static final String E_ARGS_MISSING = "E_ARGS_MISSING";
    public static final String E_ARGS_INVALID = "E_ARGS_INVALID";
    public static final String E_ARGS_RANGE = "E_ARGS_RANGE";

    private CommandArgs() {
    }

    /**
     * 将 {@code --confirm <token>} / {@code --confirm=<token>} 从参数中剥离。
     *
     * <p>用于：输入校验与授权检查阶段不应受 confirm token 影响。</p>
     */
    public static String[] stripConfirmArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }

        List<String> out = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) {
                continue;
            }
            String t = a.trim();
            if (t.isEmpty()) {
                continue;
            }

            if ("--confirm".equalsIgnoreCase(t) || "-confirm".equalsIgnoreCase(t)) {
                if (i + 1 < args.length) {
                    i++;
                }
                continue;
            }
            if (t.regionMatches(true, 0, "--confirm=", 0, "--confirm=".length())) {
                continue;
            }
            out.add(t);
        }
        return out.toArray(new String[0]);
    }

    public static int getInt(String[] args, int index, String name, int def, int min, int max) {
        return getInt(args, index, name, def, min, max, null);
    }

    public static int getInt(String[] args, int index, String name, int def, int min, int max, String usage) {
        String raw = getRaw(args, index);
        if (raw == null) {
            return def;
        }
        int v = parseInt(raw, name, usage);
        return requireRange(name, v, min, max, usage);
    }

    public static long getLong(String[] args, int index, String name, long def, long min, long max) {
        return getLong(args, index, name, def, min, max, null);
    }

    public static long getLong(String[] args, int index, String name, long def, long min, long max, String usage) {
        String raw = getRaw(args, index);
        if (raw == null) {
            return def;
        }
        long v = parseLong(raw, name, usage);
        return requireRange(name, v, min, max, usage);
    }

    public static int requireInt(String[] args, int index, String name, int min, int max, String usage) {
        String raw = requireRaw(args, index, name, usage);
        int v = parseInt(raw, name, usage);
        return requireRange(name, v, min, max, usage);
    }

    public static long requireLong(String[] args, int index, String name, long min, long max, String usage) {
        String raw = requireRaw(args, index, name, usage);
        long v = parseLong(raw, name, usage);
        return requireRange(name, v, min, max, usage);
    }

    private static String getRaw(String[] args, int index) {
        if (args == null || args.length == 0 || index < 0 || index >= args.length) {
            return null;
        }
        String raw = args[index];
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    private static String requireRaw(String[] args, int index, String name, String usage) {
        String raw = getRaw(args, index);
        if (raw == null) {
            throw new IllegalArgumentException(format(E_ARGS_MISSING,
                "Missing argument: " + safeName(name), usage));
        }
        return raw;
    }

    private static int parseInt(String raw, String name, String usage) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(format(E_ARGS_INVALID,
                "Invalid integer for " + safeName(name) + ": " + quote(raw), usage));
        }
    }

    private static long parseLong(String raw, String name, String usage) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(format(E_ARGS_INVALID,
                "Invalid long for " + safeName(name) + ": " + quote(raw), usage));
        }
    }

    private static int requireRange(String name, int value, int min, int max, String usage) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(format(E_ARGS_RANGE,
                safeName(name) + " out of range: " + value + " (min=" + min + ", max=" + max + ")", usage));
        }
        return value;
    }

    private static long requireRange(String name, long value, long min, long max, String usage) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(format(E_ARGS_RANGE,
                safeName(name) + " out of range: " + value + " (min=" + min + ", max=" + max + ")", usage));
        }
        return value;
    }

    private static String safeName(String name) {
        return name == null || name.trim().isEmpty() ? "arg" : name.trim();
    }

    private static String quote(String v) {
        if (v == null) {
            return "\"\"";
        }
        String t = v.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return "\"" + t + "\"";
    }

    private static String format(String code, String message, String usage) {
        String c = code == null || code.trim().isEmpty() ? "E_ARGS" : code.trim();
        String m = message == null ? "" : message.trim();
        String u = usage == null ? "" : usage.trim();
        if (!u.isEmpty()) {
            return c + ": " + m + ". Usage: " + u;
        }
        return c + ": " + m;
    }
}
