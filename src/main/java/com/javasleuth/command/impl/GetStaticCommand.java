package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.util.SleuthValueFormatter;
import com.javasleuth.util.WildcardMatcher;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class GetStaticCommand implements Command {
    private final Instrumentation instrumentation;

    public GetStaticCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length < 2) {
            return getHelp();
        }

        String className = args[1];
        String fieldPattern = args.length >= 3 ? args[2] : "*";
        int limit = 50;
        int deep = 1;

        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if ("--limit".equals(a) && i + 1 < args.length) {
                limit = parseInt(args[++i], 50);
            } else if ("--deep".equals(a) && i + 1 < args.length) {
                deep = parseInt(args[++i], 1);
            } else if ("-h".equals(a) || "--help".equals(a)) {
                return getHelp();
            }
        }

        Class<?> target = findLoadedClass(className);
        if (target == null) {
            target = Class.forName(className);
        }

        SleuthValueFormatter.Options opt = new SleuthValueFormatter.Options()
            .withMaxDepth(Math.max(0, deep))
            .withMaxStringLength(200)
            .withMaxCollectionItems(20)
            .withMaxMapEntries(20);

        StringBuilder sb = new StringBuilder();
        sb.append("Static fields for ").append(target.getName()).append(" (pattern=").append(fieldPattern).append(")\n");

        int count = 0;
        for (Field f : target.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (!WildcardMatcher.matches(f.getName(), fieldPattern)) {
                continue;
            }
            if (count >= limit) {
                break;
            }
            count++;

            Object v;
            try {
                if (!f.canAccess(null)) {
                    f.setAccessible(true);
                }
                v = f.get(null);
            } catch (Throwable t) {
                v = t;
            }

            String key = f.getName();
            sb.append(target.getName()).append(".").append(key).append(" = ");
            if (SleuthValueFormatter.isSensitiveKey(key)) {
                sb.append("\"****\"");
            } else if (v instanceof Throwable) {
                sb.append(SleuthValueFormatter.formatThrowable((Throwable) v, opt));
            } else {
                sb.append(SleuthValueFormatter.format(v, opt));
            }
            sb.append("\n");
        }

        if (count == 0) {
            sb.append("(no static fields matched)\n");
        }
        return sb.toString().trim();
    }

    private Class<?> findLoadedClass(String className) {
        if (className == null) {
            return null;
        }
        try {
            for (Class<?> c : instrumentation.getAllLoadedClasses()) {
                if (c != null && className.equals(c.getName())) {
                    return c;
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return null;
    }

    private int parseInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String getHelp() {
        return "GetStatic command usage:\n" +
            "  getstatic <class-name> [field-pattern] [--limit <n>] [--deep <n>]\n" +
            "Examples:\n" +
            "  getstatic com.example.Config * --limit 50 --deep 1\n";
    }

    @Override
    public String getDescription() {
        return "Read static fields (ognl-lite, read-only)";
    }
}
