package com.javasleuth.monitor;

import com.javasleuth.data.TraceResult;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class TraceInterceptor {
    private static final ConcurrentHashMap<String, BlockingQueue<TraceResult>> traceQueues = new ConcurrentHashMap<>();
    private static final ThreadLocal<AtomicLong> callDepth = ThreadLocal.withInitial(() -> new AtomicLong(0));
    private static volatile boolean enabled = false;

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

            queue.offer(result);
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

            queue.offer(result);
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

            queue.offer(result);
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
}