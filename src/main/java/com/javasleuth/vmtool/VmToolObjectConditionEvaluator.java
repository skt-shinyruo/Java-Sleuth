package com.javasleuth.vmtool;

import com.javasleuth.util.ReflectionUtils;
import com.javasleuth.util.SleuthValueFormatter;
import com.javasleuth.monitor.VmToolInterceptor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * vmtool 对象筛选条件（受控版）。
 *
 * <p>语法：</p>
 * <pre>
 * --where lhs:op:rhs
 * </pre>
 * <p>lhs 支持：</p>
 * <ul>
 *   <li>class / id / thread / ageMs</li>
 *   <li>field.xxx （读取实例字段）</li>
 * </ul>
 * <p>op 支持：eq/ne/gt/gte/lt/lte/contains/startswith/endswith</p>
 */
public final class VmToolObjectConditionEvaluator {
    public enum Operator {
        EQ,
        NE,
        GT,
        GTE,
        LT,
        LTE,
        CONTAINS,
        STARTS_WITH,
        ENDS_WITH;

        public static Operator parse(String raw) {
            if (raw == null) {
                return null;
            }
            String v = raw.trim().toLowerCase(Locale.ROOT);
            switch (v) {
                case "eq": return EQ;
                case "ne": return NE;
                case "gt": return GT;
                case "gte": return GTE;
                case "lt": return LT;
                case "lte": return LTE;
                case "contains": return CONTAINS;
                case "startswith": return STARTS_WITH;
                case "endswith": return ENDS_WITH;
                default: return null;
            }
        }
    }

    public static final class Condition {
        private final String lhs;
        private final Operator op;
        private final String rhs;

        public Condition(String lhs, Operator op, String rhs) {
            this.lhs = lhs;
            this.op = op;
            this.rhs = rhs;
        }

        public String getLhs() { return lhs; }
        public Operator getOp() { return op; }
        public String getRhs() { return rhs; }
    }

    private VmToolObjectConditionEvaluator() {}

    public static List<Condition> parse(List<String> rawConditions) {
        if (rawConditions == null || rawConditions.isEmpty()) {
            return Collections.emptyList();
        }
        List<Condition> out = new ArrayList<>();
        for (String raw : rawConditions) {
            Condition c = parseOne(raw);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    public static Condition parseOne(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        String[] parts = s.split(":", 3);
        if (parts.length != 3) {
            return null;
        }
        String lhs = parts[0].trim();
        Operator op = Operator.parse(parts[1]);
        String rhs = parts[2].trim();
        if (lhs.isEmpty() || op == null) {
            return null;
        }
        return new Condition(lhs, op, rhs);
    }

    public static boolean matches(VmToolInterceptor.TrackedInstanceInfo info,
                                  Object instance,
                                  List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Condition c : conditions) {
            if (!matchesOne(info, instance, c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesOne(VmToolInterceptor.TrackedInstanceInfo info,
                                      Object instance,
                                      Condition c) {
        Object lhsValue = resolveLhs(info, instance, c.getLhs());
        return compare(lhsValue, c.getOp(), c.getRhs());
    }

    private static Object resolveLhs(VmToolInterceptor.TrackedInstanceInfo info, Object instance, String lhs) {
        if (lhs == null) {
            return null;
        }
        String key = lhs.trim();
        switch (key) {
            case "class":
                return info != null ? info.getClassName() : null;
            case "id":
                return info != null ? VmToolInterceptor.formatIdentity(info.getIdentityHash()) : null;
            case "thread":
                return info != null ? info.getCapturedThread() : null;
            case "ageMs":
                if (info == null) {
                    return null;
                }
                long age = System.currentTimeMillis() - info.getCapturedAtMs();
                return age;
            default:
                break;
        }

        if (key.startsWith("field.") && key.length() > "field.".length()) {
            String fieldName = key.substring("field.".length());
            return readField(instance, fieldName);
        }
        return null;
    }

    private static Object readField(Object instance, String fieldName) {
        if (instance == null || fieldName == null || fieldName.trim().isEmpty()) {
            return null;
        }
        String name = fieldName.trim();
        Class<?> cur = instance.getClass();
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(name);
                if (f == null) {
                    return null;
                }
                if (Modifier.isStatic(f.getModifiers())) {
                    return null;
                }
                if (!ReflectionUtils.canAccess(f, instance)) {
                    ReflectionUtils.trySetAccessible(f);
                }
                Object v = f.get(instance);
                if (SleuthValueFormatter.isSensitiveKey(name)) {
                    return "****";
                }
                return v;
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static boolean compare(Object lhsValue, Operator op, String rhsRaw) {
        if (op == null) {
            return false;
        }
        String rhs = rhsRaw != null ? rhsRaw : "";

        if (op == Operator.EQ || op == Operator.NE) {
            boolean eq = equalsLoose(lhsValue, rhs);
            return op == Operator.EQ ? eq : !eq;
        }

        // Numeric comparisons
        if (op == Operator.GT || op == Operator.GTE || op == Operator.LT || op == Operator.LTE) {
            Double l = toDouble(lhsValue);
            Double r = toDouble(rhs);
            if (l == null || r == null) {
                return false;
            }
            switch (op) {
                case GT: return l > r;
                case GTE: return l >= r;
                case LT: return l < r;
                case LTE: return l <= r;
                default: return false;
            }
        }

        String lstr = lhsValue != null ? String.valueOf(lhsValue) : null;
        if (lstr == null) {
            return false;
        }
        switch (op) {
            case CONTAINS:
                return lstr.contains(rhs);
            case STARTS_WITH:
                return lstr.startsWith(rhs);
            case ENDS_WITH:
                return lstr.endsWith(rhs);
            default:
                return false;
        }
    }

    private static boolean equalsLoose(Object lhsValue, String rhs) {
        if (lhsValue == null) {
            return rhs == null || rhs.isEmpty() || "null".equalsIgnoreCase(rhs);
        }
        // numeric equality when possible
        Double l = toDouble(lhsValue);
        Double r = toDouble(rhs);
        if (l != null && r != null) {
            return Double.compare(l, r) == 0;
        }
        return String.valueOf(lhsValue).equals(rhs);
    }

    private static Double toDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        String s = String.valueOf(v);
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }
}

