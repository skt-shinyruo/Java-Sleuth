package com.javasleuth.monitor;

import com.javasleuth.data.WatchResult;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class WatchInterceptor {
    private static final ConcurrentHashMap<String, BlockingQueue<WatchResult>> watchQueues = new ConcurrentHashMap<>();
    private static volatile boolean enabled = false;

    public static void registerWatch(String watchId, BlockingQueue<WatchResult> queue) {
        watchQueues.put(watchId, queue);
        enabled = true;
    }

    public static void unregisterWatch(String watchId) {
        watchQueues.remove(watchId);
        enabled = !watchQueues.isEmpty();
    }

    public static void unregisterAllWatches() {
        watchQueues.clear();
        enabled = false;
    }

    public static void onMethodEntry(String watchId, String className, String methodName,
                                   String methodDesc, Object[] parameters, long startTime) {
        if (!enabled) return;

        BlockingQueue<WatchResult> queue = watchQueues.get(watchId);
        if (queue == null) return;

        try {
            WatchResult result = new WatchResult();
            result.setWatchId(watchId);
            result.setClassName(className);
            result.setMethodName(methodName);
            result.setMethodDescriptor(methodDesc);
            result.setParameters(parameters);
            result.setStartTime(startTime);
            result.setEventType(WatchResult.EventType.METHOD_ENTRY);
            result.setThreadName(Thread.currentThread().getName());
            result.setThreadId(Thread.currentThread().getId());

            queue.offer(result);
        } catch (Exception e) {
            // Silently ignore to avoid affecting the monitored application
        }
    }

    public static void onMethodExit(String watchId, String className, String methodName,
                                  String methodDesc, Object returnValue, long startTime, long duration) {
        if (!enabled) return;

        BlockingQueue<WatchResult> queue = watchQueues.get(watchId);
        if (queue == null) return;

        try {
            WatchResult result = new WatchResult();
            result.setWatchId(watchId);
            result.setClassName(className);
            result.setMethodName(methodName);
            result.setMethodDescriptor(methodDesc);
            result.setReturnValue(returnValue);
            result.setStartTime(startTime);
            result.setDuration(duration);
            result.setEventType(WatchResult.EventType.METHOD_EXIT);
            result.setThreadName(Thread.currentThread().getName());
            result.setThreadId(Thread.currentThread().getId());

            queue.offer(result);
        } catch (Exception e) {
            // Silently ignore to avoid affecting the monitored application
        }
    }

    public static void onMethodException(String watchId, String className, String methodName,
                                       String methodDesc, Throwable exception, long startTime, long duration) {
        if (!enabled) return;

        BlockingQueue<WatchResult> queue = watchQueues.get(watchId);
        if (queue == null) return;

        try {
            WatchResult result = new WatchResult();
            result.setWatchId(watchId);
            result.setClassName(className);
            result.setMethodName(methodName);
            result.setMethodDescriptor(methodDesc);
            result.setException(exception);
            result.setStartTime(startTime);
            result.setDuration(duration);
            result.setEventType(WatchResult.EventType.METHOD_EXCEPTION);
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

    public static int getActiveWatchCount() {
        return watchQueues.size();
    }
}