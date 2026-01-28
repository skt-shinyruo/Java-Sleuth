package com.javasleuth.monitor;

import com.javasleuth.data.TraceResult;
import com.javasleuth.config.ProductionConfig;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

public class TraceInterceptor {
    private static final ConcurrentHashMap<String, BlockingQueue<TraceResult>> traceQueues = new ConcurrentHashMap<>();
    private static final ThreadLocal<AtomicLong> callDepth = ThreadLocal.withInitial(() -> new AtomicLong(0));
    private static volatile boolean enabled = false;
    private static final ProductionConfig config = ProductionConfig.getInstance();
    private static final AtomicLong publishedEvents = new AtomicLong(0);
    private static final AtomicLong droppedEvents = new AtomicLong(0);
    private static final AtomicLong evictedEvents = new AtomicLong(0);
    private static final AtomicLong sampledOutEvents = new AtomicLong(0);

    public static void registerTrace(String traceId, BlockingQueue<TraceResult> queue) {
        traceQueues.put(traceId, queue);
        enabled = true;
    }

    public static void unregisterTrace(String traceId) {
        traceQueues.remove(traceId);
        enabled = !traceQueues.isEmpty();
    }

    public static void unregisterAllTraces() {
        traceQueues.clear();
        enabled = false;
    }

    public static void onMethodEntry(String traceId, String className, String methodName,
                                   String methodDesc, long startTime) {
        if (!enabled) return;

        BlockingQueue<TraceResult> queue = traceQueues.get(traceId);
        if (queue == null) return;

        try {
            long depth = callDepth.get().getAndIncrement();
            if (!passesSampleRate()) {
                return;
            }

            TraceResult result = new TraceResult();
            result.setTraceId(traceId);
            result.setClassName(className);
            result.setMethodName(methodName);
            result.setMethodDescriptor(methodDesc);
            result.setStartTime(startTime);
            result.setEventType(TraceResult.EventType.METHOD_ENTRY);
            result.setDepth((int) depth);
            result.setThreadName(Thread.currentThread().getName());
            result.setThreadId(Thread.currentThread().getId());

            offerWithPolicy(queue, result);
        } catch (Exception e) {
            // Silently ignore to avoid affecting the monitored application
        }
    }

    public static void onMethodExit(String traceId, String className, String methodName,
                                  String methodDesc, long startTime, long duration, boolean isException) {
        if (!enabled) return;

        BlockingQueue<TraceResult> queue = traceQueues.get(traceId);
        if (queue == null) return;

        try {
            long depth = callDepth.get().decrementAndGet();
            if (!passesSampleRate()) {
                return;
            }

            TraceResult result = new TraceResult();
            result.setTraceId(traceId);
            result.setClassName(className);
            result.setMethodName(methodName);
            result.setMethodDescriptor(methodDesc);
            result.setStartTime(startTime);
            result.setDuration(duration);
            result.setEventType(isException ? TraceResult.EventType.METHOD_EXCEPTION : TraceResult.EventType.METHOD_EXIT);
            result.setDepth((int) depth);
            result.setThreadName(Thread.currentThread().getName());
            result.setThreadId(Thread.currentThread().getId());

            offerWithPolicy(queue, result);
        } catch (Exception e) {
            // Silently ignore to avoid affecting the monitored application
        }
    }

    public static void onSubMethodCall(String traceId, String targetClass, String methodName,
                                     String methodDesc, long callTime) {
        if (!enabled) return;

        BlockingQueue<TraceResult> queue = traceQueues.get(traceId);
        if (queue == null) return;

        try {
            long depth = callDepth.get().get();
            if (!passesSampleRate()) {
                return;
            }

            TraceResult result = new TraceResult();
            result.setTraceId(traceId);
            result.setClassName(targetClass);
            result.setMethodName(methodName);
            result.setMethodDescriptor(methodDesc);
            result.setStartTime(callTime);
            result.setEventType(TraceResult.EventType.SUB_METHOD_CALL);
            result.setDepth((int) depth);
            result.setThreadName(Thread.currentThread().getName());
            result.setThreadId(Thread.currentThread().getId());

            offerWithPolicy(queue, result);
        } catch (Exception e) {
            // Silently ignore to avoid affecting the monitored application
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int getActiveTraceCount() {
        return traceQueues.size();
    }

    private static boolean passesSampleRate() {
        double rate = config.getTraceSampleRate();
        if (rate < 0) {
            rate = 0;
        } else if (rate > 1.0) {
            rate = 1.0;
        }
        if (rate >= 1.0) {
            return true;
        }
        boolean pass = ThreadLocalRandom.current().nextDouble() <= rate;
        if (!pass) {
            sampledOutEvents.incrementAndGet();
        }
        return pass;
    }

    private static void offerWithPolicy(BlockingQueue<TraceResult> queue, TraceResult result) {
        boolean offered = queue.offer(result);
        if (offered) {
            publishedEvents.incrementAndGet();
            return;
        }

        if (config.isTraceDropOnFull()) {
            droppedEvents.incrementAndGet();
            return;
        }

        queue.poll();
        evictedEvents.incrementAndGet();
        boolean offered2 = queue.offer(result);
        if (offered2) {
            publishedEvents.incrementAndGet();
        } else {
            droppedEvents.incrementAndGet();
        }
    }

    public static long getPublishedEventCount() {
        return publishedEvents.get();
    }

    public static long getDroppedEventCount() {
        return droppedEvents.get();
    }

    public static long getEvictedEventCount() {
        return evictedEvents.get();
    }

    public static long getSampledOutEventCount() {
        return sampledOutEvents.get();
    }
}
