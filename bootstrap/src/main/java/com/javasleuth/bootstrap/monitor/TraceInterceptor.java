package com.javasleuth.bootstrap.monitor;

import com.javasleuth.bootstrap.data.TraceResult;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

public class TraceInterceptor {
    private static final ConcurrentHashMap<String, BlockingQueue<TraceResult>> traceQueues = new ConcurrentHashMap<>();
    private static volatile boolean enabled = false;
    private static final AtomicLong publishedEvents = new AtomicLong(0);
    private static final AtomicLong droppedEvents = new AtomicLong(0);
    private static final AtomicLong evictedEvents = new AtomicLong(0);
    private static final AtomicLong sampledOutEvents = new AtomicLong(0);
    private static final ConcurrentHashMap<String, Double> traceSampleRateOverrides = new ConcurrentHashMap<>();

    private static class PerTraceState {
        final ArrayDeque<Boolean> sampleStack = new ArrayDeque<>();
    }

    private static final ThreadLocal<Map<String, PerTraceState>> perThreadState =
        ThreadLocal.withInitial(HashMap::new);

    public static void registerTrace(String traceId, BlockingQueue<TraceResult> queue) {
        registerTrace(traceId, queue, null);
    }

    public static void registerTrace(String traceId, BlockingQueue<TraceResult> queue, Double sampleRateOverride) {
        traceQueues.put(traceId, queue);
        if (sampleRateOverride != null) {
            traceSampleRateOverrides.put(traceId, sampleRateOverride);
        }
        enabled = true;
    }

    public static void unregisterTrace(String traceId) {
        traceQueues.remove(traceId);
        traceSampleRateOverrides.remove(traceId);
        enabled = !traceQueues.isEmpty();
    }

    public static void unregisterAllTraces() {
        traceQueues.clear();
        traceSampleRateOverrides.clear();
        enabled = false;
    }

    public static void onMethodEntry(String traceId, String className, String methodName,
                                   String methodDesc, long startTime) {
        if (!enabled) return;

        BlockingQueue<TraceResult> queue = traceQueues.get(traceId);
        if (queue == null) return;

        try {
            PerTraceState state = getOrCreateState(traceId);
            int depth = state.sampleStack.size();
            boolean sampled;
            if (state.sampleStack.isEmpty()) {
                // Sample decision is made at root invocation, and inherited by nested calls.
                sampled = passesSampleRate(traceId);
            } else {
                Boolean parent = state.sampleStack.peekFirst();
                sampled = parent != null && parent;
            }
            state.sampleStack.addFirst(sampled);
            if (!sampled) {
                return;
            }

            TraceResult result = new TraceResult();
            result.setTraceId(traceId);
            result.setClassName(className);
            result.setMethodName(methodName);
            result.setMethodDescriptor(methodDesc);
            result.setStartTime(startTime);
            result.setEventType(TraceResult.EventType.METHOD_ENTRY);
            result.setDepth(depth);
            result.setThreadName(Thread.currentThread().getName());
            result.setThreadId(Thread.currentThread().getId());

            offerWithPolicy(queue, result);
        } catch (Exception e) {
            // Silently ignore to avoid affecting the monitored application
        }
    }

    public static void onMethodExit(String traceId, String className, String methodName,
                                  String methodDesc, long startTime, long duration, boolean isException) {
        try {
            PerTraceState state = getState(traceId);
            if (state == null) {
                return;
            }
            Boolean sampled = state.sampleStack.pollFirst();
            if (sampled == null) {
                return;
            }
            if (state.sampleStack.isEmpty()) {
                removeState(traceId);
            }
            if (!sampled) {
                return;
            }

            BlockingQueue<TraceResult> queue = traceQueues.get(traceId);
            if (queue == null) {
                return;
            }
            int depth = state.sampleStack.size();

            TraceResult result = new TraceResult();
            result.setTraceId(traceId);
            result.setClassName(className);
            result.setMethodName(methodName);
            result.setMethodDescriptor(methodDesc);
            result.setStartTime(startTime);
            result.setDuration(duration);
            result.setEventType(isException ? TraceResult.EventType.METHOD_EXCEPTION : TraceResult.EventType.METHOD_EXIT);
            result.setDepth(depth);
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
            PerTraceState state = getState(traceId);
            if (state == null) {
                return;
            }
            Boolean sampled = state.sampleStack.peekFirst();
            if (sampled == null || !sampled) {
                return;
            }
            int depth = state.sampleStack.size();

            TraceResult result = new TraceResult();
            result.setTraceId(traceId);
            result.setClassName(targetClass);
            result.setMethodName(methodName);
            result.setMethodDescriptor(methodDesc);
            result.setStartTime(callTime);
            result.setEventType(TraceResult.EventType.SUB_METHOD_CALL);
            result.setDepth(depth);
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

    private static boolean passesSampleRate(String traceId) {
        Double override = traceId != null ? traceSampleRateOverrides.get(traceId) : null;
        double rate = override != null ? override : BootstrapMonitorConfig.getTraceSampleRate();
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

    private static PerTraceState getOrCreateState(String traceId) {
        Map<String, PerTraceState> map = perThreadState.get();
        PerTraceState state = map.get(traceId);
        if (state == null) {
            state = new PerTraceState();
            map.put(traceId, state);
        }
        return state;
    }

    private static PerTraceState getState(String traceId) {
        return perThreadState.get().get(traceId);
    }

    private static void removeState(String traceId) {
        Map<String, PerTraceState> map = perThreadState.get();
        map.remove(traceId);
        if (map.isEmpty()) {
            perThreadState.remove();
        }
    }

    private static void offerWithPolicy(BlockingQueue<TraceResult> queue, TraceResult result) {
        boolean offered = queue.offer(result);
        if (offered) {
            publishedEvents.incrementAndGet();
            return;
        }

        if (BootstrapMonitorConfig.isTraceDropOnFull()) {
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
