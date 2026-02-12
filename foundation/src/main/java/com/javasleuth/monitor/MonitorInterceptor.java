package com.javasleuth.monitor;

import com.javasleuth.config.ProductionConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class MonitorInterceptor {
    private static final ConcurrentHashMap<String, MonitorCollector> collectors = new ConcurrentHashMap<>();
    private static volatile boolean enabled = false;
    private static final ProductionConfig config = ProductionConfig.getInstance();

    private MonitorInterceptor() {}

    public static void registerMonitor(String monitorId) {
        if (monitorId == null) {
            return;
        }
        collectors.putIfAbsent(monitorId, new MonitorCollector());
        enabled = true;
    }

    public static void unregisterMonitor(String monitorId) {
        if (monitorId == null) {
            return;
        }
        collectors.remove(monitorId);
        enabled = !collectors.isEmpty();
    }

    public static void unregisterAllMonitors() {
        collectors.clear();
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int getActiveMonitorCount() {
        return collectors.size();
    }

    public static void onMethodExit(String monitorId, String className, String methodName, String methodDesc,
                                    long durationNanos, boolean exception) {
        if (!enabled) {
            return;
        }
        if (monitorId == null) {
            return;
        }
        MonitorCollector collector = collectors.get(monitorId);
        if (collector == null) {
            return;
        }
        if (!passesSampleRate()) {
            return;
        }
        String key = buildMethodKey(className, methodName, methodDesc);
        collector.statsByMethod.computeIfAbsent(key, k -> new MethodStats()).add(durationNanos, exception);
    }

    public static Map<String, MethodStatsSnapshot> snapshot(String monitorId) {
        if (monitorId == null) {
            return Collections.emptyMap();
        }
        MonitorCollector c = collectors.get(monitorId);
        if (c == null) {
            return Collections.emptyMap();
        }
        Map<String, MethodStatsSnapshot> out = new HashMap<>();
        for (Map.Entry<String, MethodStats> e : c.statsByMethod.entrySet()) {
            out.put(e.getKey(), e.getValue().snapshot());
        }
        return out;
    }

    public static void clear(String monitorId) {
        if (monitorId == null) {
            return;
        }
        MonitorCollector c = collectors.get(monitorId);
        if (c != null) {
            c.statsByMethod.clear();
        }
    }

    private static boolean passesSampleRate() {
        double rate = config.getMonitorSampleRate();
        if (rate >= 1.0) {
            return true;
        }
        if (rate <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    private static String buildMethodKey(String className, String methodName, String methodDesc) {
        String c = className == null ? "?" : className;
        String m = methodName == null ? "?" : methodName;
        String d = methodDesc == null ? "" : methodDesc;
        return c + "." + m + d;
    }

    private static final class MonitorCollector {
        private final ConcurrentHashMap<String, MethodStats> statsByMethod = new ConcurrentHashMap<>();
    }

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
            count.increment();
            totalNanos.add(Math.max(0, durationNanos));
            if (exception) {
                exceptionCount.increment();
            }

            long d = Math.max(0, durationNanos);
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
}
