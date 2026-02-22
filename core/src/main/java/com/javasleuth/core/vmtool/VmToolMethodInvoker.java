package com.javasleuth.core.vmtool;

import com.javasleuth.foundation.util.ReflectionUtils;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * vmtool 方法调用（简化版，面向排障验证）。
 *
 * <p>限制与安全边界：</p>
 * <ul>
 *   <li>仅支持基础类型/包装类型/boolean/String/enum 以及 null（引用类型）参数</li>
 *   <li>默认拒绝对高风险 JDK 类执行调用（可通过 --unsafe 显式放开）</li>
 * </ul>
 */
public final class VmToolMethodInvoker {
    public static final class Invocation {
        private final Method method;
        private final Object[] args;

        private Invocation(Method method, Object[] args) {
            this.method = method;
            this.args = args;
        }

        public Method getMethod() { return method; }
        public Object[] getArgs() { return args; }
    }

    private static final class ScoredInvocation {
        final Invocation inv;
        final int score;

        ScoredInvocation(Invocation inv, int score) {
            this.inv = inv;
            this.score = score;
        }
    }

    private VmToolMethodInvoker() {}

    public static Object invokeInstance(Object target,
                                        String methodName,
                                        List<String> argTokens,
                                        boolean declared,
                                        boolean unsafe) throws Exception {
        if (target == null) {
            throw new IllegalArgumentException("target is null");
        }
        if (methodName == null || methodName.trim().isEmpty()) {
            throw new IllegalArgumentException("methodName is empty");
        }
        Class<?> type = target.getClass();
        Invocation inv = resolveInvocation(type, false, methodName.trim(), argTokens, declared, unsafe);
        Method m = inv.getMethod();
        if (!ReflectionUtils.canAccess(m, target)) {
            ReflectionUtils.trySetAccessible(m);
        }
        return m.invoke(target, inv.getArgs());
    }

    public static Object invokeStatic(Class<?> type,
                                      String methodName,
                                      List<String> argTokens,
                                      boolean declared,
                                      boolean unsafe) throws Exception {
        if (type == null) {
            throw new IllegalArgumentException("type is null");
        }
        if (methodName == null || methodName.trim().isEmpty()) {
            throw new IllegalArgumentException("methodName is empty");
        }
        Invocation inv = resolveInvocation(type, true, methodName.trim(), argTokens, declared, unsafe);
        Method m = inv.getMethod();
        if (!ReflectionUtils.canAccess(m, null)) {
            ReflectionUtils.trySetAccessible(m);
        }
        return m.invoke(null, inv.getArgs());
    }

    public static Invocation resolveInvocation(Class<?> type,
                                               boolean isStatic,
                                               String methodName,
                                               List<String> argTokens,
                                               boolean declared,
                                               boolean unsafe) {
        if (type == null) {
            throw new IllegalArgumentException("type is null");
        }
        if (!unsafe && isForbiddenTarget(type, methodName)) {
            throw new SecurityException("Refused to invoke on forbidden target: " + type.getName() + "#" + methodName +
                " (use --unsafe to override)");
        }

        List<String> tokens = argTokens != null ? argTokens : new ArrayList<>();
        int argc = tokens.size();

        Method[] methods;
        try {
            methods = declared ? type.getDeclaredMethods() : type.getMethods();
        } catch (Throwable t) {
            methods = new Method[0];
        }

        List<Method> candidates = new ArrayList<>();
        for (Method m : methods) {
            if (m == null) {
                continue;
            }
            if (!m.getName().equals(methodName)) {
                continue;
            }
            if (m.isSynthetic() || m.isBridge()) {
                continue;
            }
            if (Modifier.isStatic(m.getModifiers()) != isStatic) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p == null || p.length != argc) {
                continue;
            }
            candidates.add(m);
        }

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No matching method: " + type.getName() + "#" + methodName + " argc=" + argc);
        }

        List<ScoredInvocation> scored = new ArrayList<>();
        for (Method m : candidates) {
            Object[] args = new Object[argc];
            int totalScore = 0;
            boolean ok = true;
            Class<?>[] paramTypes = m.getParameterTypes();
            for (int i = 0; i < argc; i++) {
                String raw = tokens.get(i);
                ConvertedArg ca = convert(raw, paramTypes[i]);
                if (ca == null) {
                    ok = false;
                    break;
                }
                args[i] = ca.value;
                totalScore += ca.score;
            }
            if (ok) {
                scored.add(new ScoredInvocation(new Invocation(m, args), totalScore));
            }
        }

        if (scored.isEmpty()) {
            throw new IllegalArgumentException("No overload can accept provided args for: " + type.getName() + "#" + methodName +
                " args=" + tokens);
        }

        scored.sort(Comparator.comparingInt(a -> a != null ? a.score : Integer.MAX_VALUE));
        ScoredInvocation best = scored.get(0);
        if (scored.size() > 1) {
            ScoredInvocation second = scored.get(1);
            if (second != null && second.score == best.score) {
                throw new IllegalArgumentException("Ambiguous overload for: " + type.getName() + "#" + methodName +
                    " args=" + tokens + ". Candidates: " + formatCandidates(scored, 5));
            }
        }
        return best.inv;
    }

    private static final class ConvertedArg {
        final Object value;
        final int score;

        ConvertedArg(Object value, int score) {
            this.value = value;
            this.score = score;
        }
    }

    private static ConvertedArg convert(String raw, Class<?> targetType) {
        if (targetType == null) {
            return null;
        }
        if (raw == null) {
            raw = "";
        }
        String token = raw.trim();

        if ("null".equalsIgnoreCase(token)) {
            if (targetType.isPrimitive()) {
                return null;
            }
            return new ConvertedArg(null, 0);
        }

        if (targetType == String.class || CharSequence.class.isAssignableFrom(targetType)) {
            return new ConvertedArg(token, 2);
        }

        if (targetType == boolean.class || targetType == Boolean.class) {
            if ("true".equalsIgnoreCase(token) || "false".equalsIgnoreCase(token)) {
                return new ConvertedArg(Boolean.parseBoolean(token), 0);
            }
            return null;
        }

        if (targetType == char.class || targetType == Character.class) {
            if (token.length() == 1) {
                return new ConvertedArg(token.charAt(0), 0);
            }
            return null;
        }

        // numeric
        ConvertedArg numeric = convertNumeric(token, targetType);
        if (numeric != null) {
            return numeric;
        }

        if (targetType.isEnum()) {
            @SuppressWarnings("unchecked")
            Class<? extends Enum> enumType = (Class<? extends Enum>) targetType;
            try {
                Enum<?> v = Enum.valueOf(enumType, token);
                return new ConvertedArg(v, 1);
            } catch (Exception ignore) {
                return null;
            }
        }

        if (targetType == Object.class) {
            // Fallback: pass as String
            return new ConvertedArg(token, 5);
        }

        // Not supported: complex reference types (keep it safe and predictable)
        return null;
    }

    private static ConvertedArg convertNumeric(String token, Class<?> targetType) {
        try {
            if (targetType == byte.class || targetType == Byte.class) {
                return new ConvertedArg(Byte.parseByte(token), 0);
            }
            if (targetType == short.class || targetType == Short.class) {
                return new ConvertedArg(Short.parseShort(token), 0);
            }
            if (targetType == int.class || targetType == Integer.class) {
                return new ConvertedArg(Integer.parseInt(token), 0);
            }
            if (targetType == long.class || targetType == Long.class) {
                return new ConvertedArg(Long.parseLong(token), 0);
            }
            if (targetType == float.class || targetType == Float.class) {
                return new ConvertedArg(Float.parseFloat(token), 0);
            }
            if (targetType == double.class || targetType == Double.class) {
                return new ConvertedArg(Double.parseDouble(token), 0);
            }
        } catch (NumberFormatException ignore) {
            return null;
        }
        return null;
    }

    private static boolean isForbiddenTarget(Class<?> type, String methodName) {
        if (type == null) {
            return true;
        }
        String n = type.getName();
        String m = methodName != null ? methodName.trim() : "";

        // Class-level deny list (dangerous / side-effect heavy)
        List<String> forbiddenPrefixes = Arrays.asList(
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.System",
            "java.lang.ClassLoader",
            "java.lang.reflect.",
            "java.security.",
            "sun.misc.Unsafe",
            "jdk.internal."
        );
        for (String p : forbiddenPrefixes) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (n.startsWith(p)) {
                return true;
            }
        }

        // Method-level deny list on common JDK types
        String lower = m.toLowerCase(Locale.ROOT);
        if ("exit".equals(lower) || "halt".equals(lower) || "exec".equals(lower) ||
            "load".equals(lower) || "loadlibrary".equals(lower) ||
            "setsecuritymanager".equals(lower)) {
            return true;
        }

        return false;
    }

    private static String formatCandidates(List<ScoredInvocation> scored, int limit) {
        if (scored == null || scored.isEmpty()) {
            return "[]";
        }
        int n = Math.min(limit, scored.size());
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < n; i++) {
            ScoredInvocation si = scored.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            Method m = si != null && si.inv != null ? si.inv.getMethod() : null;
            sb.append(m != null ? m.toString() : "<null>");
        }
        if (scored.size() > n) {
            sb.append(", ... +").append(scored.size() - n);
        }
        sb.append("]");
        return sb.toString();
    }
}
