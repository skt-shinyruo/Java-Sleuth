package com.javasleuth.monitor;

import com.javasleuth.data.WatchResult;
import com.javasleuth.util.SleuthValueFormatter;
import com.javasleuth.util.SleuthValueSnapshotter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class WatchInterceptor {
    private static final ConcurrentHashMap<String, BlockingQueue<WatchResult>> watchQueues = new ConcurrentHashMap<>();
    private static volatile boolean enabled = false;
    private static final AtomicLong publishedEvents = new AtomicLong(0);
    private static final AtomicLong droppedEvents = new AtomicLong(0);
    private static final AtomicLong evictedEvents = new AtomicLong(0);

    private static final SleuthValueFormatter.Options SNAPSHOT_OPTIONS =
        new SleuthValueFormatter.Options()
            .withMaxDepth(2)
            .withMaxStringLength(200)
            .withMaxCollectionItems(20)
            .withMaxMapEntries(20);

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
        if (BootstrapMonitorConfig.isWatchDropOnFull() && queue.remainingCapacity() <= 0) {
            droppedEvents.incrementAndGet();
            return;
        }

        try {
            WatchResult result = new WatchResult();
            result.setWatchId(watchId);
            result.setClassName(className);
            result.setMethodName(methodName);
            result.setMethodDescriptor(methodDesc);
            result.setParameters(SleuthValueSnapshotter.snapshotParameters(parameters, SNAPSHOT_OPTIONS));
            result.setStartTime(startTime);
            result.setEventType(WatchResult.EventType.METHOD_ENTRY);
            result.setThreadName(Thread.currentThread().getName());
            result.setThreadId(Thread.currentThread().getId());

            offerWithPolicy(queue, result);
        } catch (Exception e) {
            // Silently ignore to avoid affecting the monitored application
        }
    }

    public static void onMethodExit(String watchId, String className, String methodName,
                                  String methodDesc, Object returnValue, long startTime, long duration) {
        if (!enabled) return;

        BlockingQueue<WatchResult> queue = watchQueues.get(watchId);
        if (queue == null) return;
        if (BootstrapMonitorConfig.isWatchDropOnFull() && queue.remainingCapacity() <= 0) {
            droppedEvents.incrementAndGet();
            return;
        }

        try {
            WatchResult result = new WatchResult();
            result.setWatchId(watchId);
            result.setClassName(className);
            result.setMethodName(methodName);
            result.setMethodDescriptor(methodDesc);
            result.setReturnValue(SleuthValueSnapshotter.snapshotValue(returnValue, SNAPSHOT_OPTIONS));
            result.setStartTime(startTime);
            result.setDuration(duration);
            result.setEventType(WatchResult.EventType.METHOD_EXIT);
            result.setThreadName(Thread.currentThread().getName());
            result.setThreadId(Thread.currentThread().getId());

            offerWithPolicy(queue, result);
        } catch (Exception e) {
            // Silently ignore to avoid affecting the monitored application
        }
    }

    public static void onMethodException(String watchId, String className, String methodName,
                                       String methodDesc, Throwable exception, long startTime, long duration) {
        if (!enabled) return;

        BlockingQueue<WatchResult> queue = watchQueues.get(watchId);
        if (queue == null) return;
        if (BootstrapMonitorConfig.isWatchDropOnFull() && queue.remainingCapacity() <= 0) {
            droppedEvents.incrementAndGet();
            return;
        }

        try {
            WatchResult result = new WatchResult();
            result.setWatchId(watchId);
            result.setClassName(className);
            result.setMethodName(methodName);
            result.setMethodDescriptor(methodDesc);
            result.setException(SleuthValueSnapshotter.snapshotThrowable(exception, SNAPSHOT_OPTIONS));
            result.setStartTime(startTime);
            result.setDuration(duration);
            result.setEventType(WatchResult.EventType.METHOD_EXCEPTION);
            result.setThreadName(Thread.currentThread().getName());
            result.setThreadId(Thread.currentThread().getId());

            offerWithPolicy(queue, result);
        } catch (Exception e) {
            // Silently ignore to avoid affecting the monitored application
        }
    }

    private static void offerWithPolicy(BlockingQueue<WatchResult> queue, WatchResult result) {
        boolean offered = queue.offer(result);
        if (offered) {
            publishedEvents.incrementAndGet();
            return;
        }

        if (BootstrapMonitorConfig.isWatchDropOnFull()) {
            droppedEvents.incrementAndGet();
            return;
        }

        // Drop oldest and try again
        queue.poll();
        evictedEvents.incrementAndGet();
        boolean offered2 = queue.offer(result);
        if (offered2) {
            publishedEvents.incrementAndGet();
        } else {
            droppedEvents.incrementAndGet();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int getActiveWatchCount() {
        return watchQueues.size();
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
}
