package com.javasleuth.util;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 对象字段检视（best-effort）。
 *
 * <p>设计目标：</p>
 * <ul>
 *   <li>不调用业务方法（避免副作用），仅通过反射读取字段</li>
 *   <li>限制字段数量与递归深度，避免输出爆炸</li>
 *   <li>对常见敏感字段名进行脱敏</li>
 * </ul>
 */
public final class SleuthObjectInspector {
    public static final class Options {
        private int maxDepth = 1;
        private int maxStringLength = 200;
        private int maxCollectionItems = 20;
        private int maxMapEntries = 20;
        private int maxFields = 80;
        private boolean includeStatic = false;
        private boolean includeInherited = true;

        public int getMaxDepth() { return maxDepth; }
        public int getMaxStringLength() { return maxStringLength; }
        public int getMaxCollectionItems() { return maxCollectionItems; }
        public int getMaxMapEntries() { return maxMapEntries; }
        public int getMaxFields() { return maxFields; }
        public boolean isIncludeStatic() { return includeStatic; }
        public boolean isIncludeInherited() { return includeInherited; }

        public Options withMaxDepth(int maxDepth) { this.maxDepth = Math.max(0, maxDepth); return this; }
        public Options withMaxStringLength(int maxStringLength) { this.maxStringLength = Math.max(0, maxStringLength); return this; }
        public Options withMaxCollectionItems(int maxCollectionItems) { this.maxCollectionItems = Math.max(0, maxCollectionItems); return this; }
        public Options withMaxMapEntries(int maxMapEntries) { this.maxMapEntries = Math.max(0, maxMapEntries); return this; }
        public Options withMaxFields(int maxFields) { this.maxFields = Math.max(1, maxFields); return this; }
        public Options withIncludeStatic(boolean includeStatic) { this.includeStatic = includeStatic; return this; }
        public Options withIncludeInherited(boolean includeInherited) { this.includeInherited = includeInherited; return this; }
    }

    private SleuthObjectInspector() {}

    public static String inspect(Object target, Options options) {
        if (target == null) {
            return "null";
        }
        Options opt = options != null ? options : new Options();
        SleuthValueFormatter.Options fmt = new SleuthValueFormatter.Options()
            .withMaxDepth(opt.getMaxDepth())
            .withMaxStringLength(opt.getMaxStringLength())
            .withMaxCollectionItems(opt.getMaxCollectionItems())
            .withMaxMapEntries(opt.getMaxMapEntries());

        Class<?> type = target.getClass();
        StringBuilder sb = new StringBuilder();
        sb.append("Object Inspect\n");
        sb.append("Type: ").append(type.getName()).append("\n");
        sb.append("Identity: ").append(Integer.toHexString(System.identityHashCode(target))).append("\n");
        sb.append("Fields:\n");

        List<Field> fields = collectFields(type, opt.isIncludeInherited());
        int shown = 0;
        int max = opt.getMaxFields();
        for (Field f : fields) {
            if (shown >= max) {
                break;
            }
            if (f == null) {
                continue;
            }
            if (f.isSynthetic()) {
                continue;
            }
            if (!opt.isIncludeStatic() && Modifier.isStatic(f.getModifiers())) {
                continue;
            }

            String name = f.getName();
            Object value;
            try {
                if (!ReflectionUtils.canAccess(f, target)) {
                    ReflectionUtils.trySetAccessible(f);
                }
                value = f.get(target);
            } catch (Throwable t) {
                value = t;
            }

            sb.append("  ")
                .append(f.getDeclaringClass().getName())
                .append(".")
                .append(name)
                .append(" = ");

            if (SleuthValueFormatter.isSensitiveKey(name)) {
                sb.append("\"****\"");
            } else if (value instanceof Throwable) {
                sb.append(SleuthValueFormatter.formatThrowable((Throwable) value, fmt));
            } else {
                sb.append(SleuthValueFormatter.format(value, fmt));
            }
            sb.append("\n");
            shown++;
        }

        if (shown == 0) {
            sb.append("  (no fields)\n");
        } else if (shown < fields.size()) {
            sb.append("  ... (truncated, maxFields=").append(max).append(")\n");
        }

        return sb.toString().trim();
    }

    private static List<Field> collectFields(Class<?> type, boolean includeInherited) {
        List<Field> out = new ArrayList<>();
        Class<?> cur = type;
        while (cur != null) {
            try {
                Field[] declared = cur.getDeclaredFields();
                if (declared != null && declared.length > 0) {
                    out.addAll(Arrays.asList(declared));
                }
            } catch (Throwable ignore) {
                // best-effort
            }
            if (!includeInherited) {
                break;
            }
            cur = cur.getSuperclass();
            if (cur == Object.class) {
                // stop before Object to reduce noise
                break;
            }
        }
        out.sort(Comparator.comparing(Field::getName, Comparator.nullsLast(String::compareTo)));
        return out;
    }
}

