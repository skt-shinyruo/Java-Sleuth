package com.javasleuth.util;

import com.javasleuth.data.WatchResult;
import com.javasleuth.monitor.TraceAggregator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Very small, controlled condition evaluator.
 *
 * <p>Condition syntax: lhs:op:rhs
 * - lhs: whitelisted fields, e.g. cost, class, method, thread, threadId, event, return, throw, params[0]
 * - op: eq|ne|gt|gte|lt|lte|contains|startswith|endswith
 * - rhs: literal string/number
 *
 * <p>Multiple --condition are AND-ed.
 */
public final class SleuthConditionEvaluator {
    private SleuthConditionEvaluator() {}

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

    public static List<Condition> parseConditions(List<String> rawConditions) {
        if (rawConditions == null || rawConditions.isEmpty()) {
            return Collections.emptyList();
        }
        List<Condition> out = new ArrayList<>();
        for (String raw : rawConditions) {
            Condition c = parseCondition(raw);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    public static Condition parseCondition(String raw) {
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

    public static boolean matchesWatch(WatchResult r, List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Condition c : conditions) {
            if (!matchesWatchSingle(r, c)) {
                return false;
            }
        }
        return true;
    }

    public static boolean matchesTraceInvocation(TraceAggregator.Invocation inv, List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Condition c : conditions) {
            if (!matchesTraceSingle(inv, c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesWatchSingle(WatchResult r, Condition c) {
        Object lhsValue = resolveWatchLhs(r, c.getLhs());
        return compare(lhsValue, c.getOp(), c.getRhs());
    }

    private static boolean matchesTraceSingle(TraceAggregator.Invocation inv, Condition c) {
        Object lhsValue = resolveTraceLhs(inv, c.getLhs());
        return compare(lhsValue, c.getOp(), c.getRhs());
    }

    private static Object resolveWatchLhs(WatchResult r, String lhs) {
        if (r == null || lhs == null) {
            return null;
        }
        String key = lhs.trim();
        switch (key) {
            case "cost":
                return r.getDuration();
            case "class":
                return r.getClassName();
            case "method":
                return r.getMethodName();
            case "thread":
                return r.getThreadName();
            case "threadId":
                return r.getThreadId();
            case "event":
                return r.getEventType() != null ? r.getEventType().name() : null;
            case "return":
                return r.getReturnValue();
            case "throw":
            case "exception":
                return r.getException();
            default:
                break;
        }

        // params[0] / params[1] ...
        if (key.startsWith("params[") && key.endsWith("]")) {
            String idxStr = key.substring("params[".length(), key.length() - 1);
            try {
                int idx = Integer.parseInt(idxStr);
                Object[] params = r.getParameters();
                if (params == null || idx < 0 || idx >= params.length) {
                    return null;
                }
                return params[idx];
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // p0 / p1 ...
        if (key.startsWith("p") && key.length() > 1) {
            try {
                int idx = Integer.parseInt(key.substring(1));
                Object[] params = r.getParameters();
                if (params == null || idx < 0 || idx >= params.length) {
                    return null;
                }
                return params[idx];
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    private static Object resolveTraceLhs(TraceAggregator.Invocation inv, String lhs) {
        if (inv == null || lhs == null || inv.getRoot() == null) {
            return null;
        }
        String key = lhs.trim();
        switch (key) {
            case "cost":
                return inv.getRoot().getDuration();
            case "class":
                return inv.getRoot().getClassName();
            case "method":
                return inv.getRoot().getMethodName();
            case "thread":
                return inv.getThreadName();
            case "threadId":
                return inv.getThreadId();
            case "exception":
                return inv.getRoot().isException() ? "true" : "false";
            case "depth":
                return computeDepth(inv.getRoot(), 1);
            case "nodes":
                return countNodes(inv.getRoot());
            default:
                return null;
        }
    }

    private static int computeDepth(TraceAggregator.Node node, int current) {
        if (node == null) {
            return current;
        }
        int max = current;
        for (TraceAggregator.Item child : node.getChildren()) {
            if (child instanceof TraceAggregator.Node) {
                int d = computeDepth((TraceAggregator.Node) child, current + 1);
                if (d > max) {
                    max = d;
                }
            } else {
                if (current + 1 > max) {
                    max = current + 1;
                }
            }
        }
        return max;
    }

    private static int countNodes(TraceAggregator.Node node) {
        if (node == null) {
            return 0;
        }
        int count = 1;
        for (TraceAggregator.Item child : node.getChildren()) {
            if (child instanceof TraceAggregator.Node) {
                count += countNodes((TraceAggregator.Node) child);
            } else {
                count += 1;
            }
        }
        return count;
    }

    private static boolean compare(Object lhsValue, Operator op, String rhs) {
        if (op == null) {
            return false;
        }

        switch (op) {
            case CONTAINS:
            case STARTS_WITH:
            case ENDS_WITH:
                String ls = lhsValue == null ? "" : String.valueOf(lhsValue);
                String rs = rhs == null ? "" : rhs;
                if (op == Operator.CONTAINS) {
                    return ls.contains(rs);
                }
                if (op == Operator.STARTS_WITH) {
                    return ls.startsWith(rs);
                }
                return ls.endsWith(rs);
            case EQ:
            case NE:
                // numeric if possible
                Double ln = toNumber(lhsValue);
                Double rn = toNumber(rhs);
                if (ln != null && rn != null) {
                    boolean eq = Double.compare(ln, rn) == 0;
                    return op == Operator.EQ ? eq : !eq;
                }
                String lsv = lhsValue == null ? null : String.valueOf(lhsValue);
                boolean eq = (lsv == null && rhs == null) || (lsv != null && lsv.equals(rhs));
                return op == Operator.EQ ? eq : !eq;
            case GT:
            case GTE:
            case LT:
            case LTE:
                Double l = toNumber(lhsValue);
                Double r = toNumber(rhs);
                if (l == null || r == null) {
                    return false;
                }
                int cmp = Double.compare(l, r);
                switch (op) {
                    case GT: return cmp > 0;
                    case GTE: return cmp >= 0;
                    case LT: return cmp < 0;
                    case LTE: return cmp <= 0;
                    default: return false;
                }
            default:
                return false;
        }
    }

    private static Double toNumber(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            return toNumber((String) v);
        }
        return null;
    }

    private static Double toNumber(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        // Support simple duration suffix for cost comparisons: ns/us/ms/s
        String lower = t.toLowerCase(Locale.ROOT);
        long factor = 1;
        if (lower.endsWith("ns")) {
            factor = 1L;
            lower = lower.substring(0, lower.length() - 2).trim();
        } else if (lower.endsWith("us") || lower.endsWith("μs")) {
            factor = 1_000L;
            lower = lower.substring(0, lower.length() - 2).trim();
        } else if (lower.endsWith("ms")) {
            factor = 1_000_000L;
            lower = lower.substring(0, lower.length() - 2).trim();
        } else if (lower.endsWith("s")) {
            factor = 1_000_000_000L;
            lower = lower.substring(0, lower.length() - 1).trim();
        }
        try {
            double v = Double.parseDouble(lower);
            return v * factor;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
