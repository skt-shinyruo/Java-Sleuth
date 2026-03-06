package com.javasleuth.core.spy.listener;

import com.javasleuth.core.spy.SleuthAdviceListener;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Core-side implementation of monitor stats aggregation.
 *
 * <p>Collects per-method count/exception/max/min/total duration nanos.</p>
 */
public final class MonitorAdviceListener implements SleuthAdviceListener {

    public static final class MethodStatsSnapshot {
        private final long count;
        private final long exceptionCount;
        private final long totalNanos;
        private final long maxNanos;
        private final long minNanos;

        public MethodStatsSnapshot(long count, long exceptionCount, long totalNanos, long maxNanos, long minNanos) {
            this.count = count;
            this.exceptionCount = exceptionCount;
            this.totalNanos = totalNanos;
            this.maxNanos = maxNanos;
            this.minNanos = minNanos;
        }

        public long getCount() { return count; }
        public long getExceptionCount() { return exceptionCount; }
        public long getTotalNanos() { return totalNanos; }
        public long getMaxNanos() { return maxNanos; }
        public long getMinNanos() { return minNanos; }
    }

    private static final class MethodStats {
        private final LongAdder count = new LongAdder();
        private final LongAdder exceptionCount = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong(0);
        private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);

        void add(long durationNanos, boolean exception) {
            long d = Math.max(0, durationNanos);
            count.increment();
            totalNanos.add(d);
            if (exception) {
                exceptionCount.increment();
            }

            long curMax = maxNanos.get();
            while (d > curMax && !maxNanos.compareAndSet(curMax, d)) {
                curMax = maxNanos.get();
            }

            long curMin = minNanos.get();
            while (d < curMin && !minNanos.compareAndSet(curMin, d)) {
                curMin = minNanos.get();
            }
        }

        MethodStatsSnapshot snapshot() {
            long c = count.sum();
            long ex = exceptionCount.sum();
            long total = totalNanos.sum();
            long max = maxNanos.get();
            long min = minNanos.get();
            if (min == Long.MAX_VALUE) {
                min = 0;
            }
            return new MethodStatsSnapshot(c, ex, total, max, min);
        }
    }

    private final ConcurrentHashMap<String, MethodStats> statsByMethod = new ConcurrentHashMap<>();

    @Override
    public void onExit(
        Class<?> clazz,
        String methodInfo,
        Object target,
        Object[] args,
        Object returnObject,
        boolean returnCaptured,
        long startNanos,
        long durationNanos
    ) {
        onExit0(clazz, methodInfo, durationNanos, false);
    }

    @Override
    public void onExceptionExit(
        Class<?> clazz,
        String methodInfo,
        Object target,
        Object[] args,
        Throwable throwable,
        long startNanos,
        long durationNanos
    ) {
        onExit0(clazz, methodInfo, durationNanos, true);
    }

    private void onExit0(Class<?> clazz, String methodInfo, long durationNanos, boolean exception) {
        try {
            MethodParts mp = MethodParts.parse(methodInfo);
            String key = buildMethodKey(safeClassName(clazz), mp.methodName, mp.methodDesc);
            statsByMethod.computeIfAbsent(key, k -> new MethodStats()).add(durationNanos, exception);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    public Map<String, MethodStatsSnapshot> snapshot() {
        if (statsByMethod.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, MethodStatsSnapshot> out = new HashMap<>();
        for (Map.Entry<String, MethodStats> e : statsByMethod.entrySet()) {
            MethodStats s = e.getValue();
            if (s == null) {
                continue;
            }
            out.put(e.getKey(), s.snapshot());
        }
        return out;
    }

    public void clear() {
        statsByMethod.clear();
    }

    private static String buildMethodKey(String className, String methodName, String methodDesc) {
        String c = className == null ? "?" : className;
        String m = methodName == null ? "?" : methodName;
        String d = methodDesc == null ? "" : methodDesc;
        return c + "." + m + d;
    }

    private static String safeClassName(Class<?> clazz) {
        if (clazz == null) {
            return "<unknown>";
        }
        try {
            return clazz.getName();
        } catch (Throwable ignore) {
            return "<unknown>";
        }
    }

    private static final class MethodParts {
        final String methodName;
        final String methodDesc;

        private MethodParts(String methodName, String methodDesc) {
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        static MethodParts parse(String methodInfo) {
            if (methodInfo == null) {
                return new MethodParts("?", "");
            }
            int sep = methodInfo.indexOf('|');
            if (sep < 0) {
                return new MethodParts(methodInfo, "");
            }
            return new MethodParts(methodInfo.substring(0, sep), methodInfo.substring(sep + 1));
        }
    }
}

