package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.foundation.util.ReflectionUtils;
import com.javasleuth.bootstrap.util.SleuthValueFormatter;
import com.javasleuth.foundation.util.LoadedClassResolver;
import com.javasleuth.foundation.util.WildcardMatcher;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

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

        List<String> positional = new ArrayList<String>();
        Integer loaderId = null;
        boolean allowFirst = false;
        int limit = 50;
        int deep = 1;

        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--limit".equals(a) && i + 1 < args.length) {
                limit = parseInt(args[++i], 50);
            } else if ("--deep".equals(a) && i + 1 < args.length) {
                deep = parseInt(args[++i], 1);
            } else if (("--loader".equals(a) || "--loader-id".equals(a) || "--loader-hash".equals(a)) && i + 1 < args.length) {
                String raw = args[++i];
                loaderId = LoadedClassResolver.parseLoaderId(raw);
                if (loaderId == null) {
                    return "Invalid --loader value: " + raw + " (expected: bootstrap/null/0x1234/1234)";
                }
            } else if ("--first".equals(a) || "--unsafe-first".equals(a)) {
                allowFirst = true;
            } else if ("-h".equals(a) || "--help".equals(a)) {
                return getHelp();
            } else {
                positional.add(a);
            }
        }

        if (positional.isEmpty()) {
            return getHelp();
        }
        String classPattern = positional.get(0);
        String fieldPattern = positional.size() >= 2 ? positional.get(1) : "*";

        LoadedClassResolver.Candidate resolved;
        try {
            resolved = LoadedClassResolver.resolveSingle(instrumentation, classPattern, loaderId, false, 200, allowFirst);
        } catch (LoadedClassResolver.ResolutionException e) {
            return e.getMessage() +
                "\nCandidates:\n" + LoadedClassResolver.formatCandidates(e.getCandidates(), 10) +
                "\nHint: use --loader <loaderId> (e.g. --loader 0x1234 or --loader bootstrap) or --first";
        }
        Class<?> target = resolved.getClazz();

        SleuthValueFormatter.Options opt = new SleuthValueFormatter.Options()
            .withMaxDepth(Math.max(0, deep))
            .withMaxStringLength(200)
            .withMaxCollectionItems(20)
            .withMaxMapEntries(20);

        StringBuilder sb = new StringBuilder();
        sb.append("Static fields for ").append(target.getName())
            .append(" (loaderId=").append(LoadedClassResolver.formatLoaderId(resolved.getLoaderId()))
            .append(", pattern=").append(fieldPattern).append(")\n");

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
                if (!ReflectionUtils.canAccess(f, null)) {
                    ReflectionUtils.trySetAccessible(f);
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
            "  getstatic <class-pattern> [field-pattern] [--loader <loaderId>] [--first] [--limit <n>] [--deep <n>]\n" +
            "Examples:\n" +
            "  getstatic com.example.Config * --limit 50 --deep 1\n" +
            "  getstatic --loader 0x1234 com.example.Config secret\n";
    }

    @Override
    public String getDescription() {
        return "Read static fields (ognl-lite, read-only)";
    }
}
