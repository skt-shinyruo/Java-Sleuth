package com.javasleuth.bootstrap.monitor;

import com.javasleuth.bootstrap.data.TtRecord;
import com.javasleuth.bootstrap.util.RingBuffer;
import com.javasleuth.bootstrap.util.SleuthValueFormatter;
import com.javasleuth.bootstrap.util.SleuthValueSnapshotter;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class TtInterceptor {
    private static final ConcurrentHashMap<String, BlockingQueue<TtRecord>> ttQueues = new ConcurrentHashMap<>();
    private static volatile boolean enabled = false;

    private static final AtomicLong recordSeq = new AtomicLong(1);
    private static volatile RingBuffer<TtRecord> records = new RingBuffer<>(2000);

    private static final AtomicLong published = new AtomicLong(0);
    private static final AtomicLong dropped = new AtomicLong(0);
    private static final AtomicLong evicted = new AtomicLong(0);

    private static final SleuthValueFormatter.Options SNAPSHOT_OPTIONS =
        new SleuthValueFormatter.Options()
            .withMaxDepth(2)
            .withMaxStringLength(200)
            .withMaxCollectionItems(20)
            .withMaxMapEntries(20);

    private TtInterceptor() {}

    public static void register(String ttId, BlockingQueue<TtRecord> queue) {
        if (ttId == null || queue == null) {
            return;
        }
        ttQueues.put(ttId, queue);
        enabled = true;
    }

    public static void unregister(String ttId) {
        if (ttId == null) {
            return;
        }
        ttQueues.remove(ttId);
        enabled = !ttQueues.isEmpty();
    }

    public static void unregisterAll() {
        ttQueues.clear();
        enabled = false;
    }

    /**
     * detach/shutdown 时使用的重置入口（best-effort）。
     *
     * <p>目标：</p>
     * <ul>
     *   <li>清理队列与缓存，避免跨 attach 残留影响后续诊断</li>
     *   <li>重置统计计数，避免 status 指标跨 attach 累积造成误判</li>
     * </ul>
     */
    public static void resetForDetach() {
        try {
            unregisterAll();
        } catch (Exception ignore) {
            // ignore
        }
        try {
            clear();
        } catch (Exception ignore) {
            // ignore
        }
        resetMetrics();
        try {
            recordSeq.set(1);
        } catch (Exception ignore) {
            // ignore
        }
    }

    /**
     * 重置统计计数（不会影响功能开关）。
     */
    public static void resetMetrics() {
        try {
            published.set(0);
            dropped.set(0);
            evicted.set(0);
        } catch (Exception ignore) {
            // ignore
        }
    }

    public static int getActiveTtCount() {
        return ttQueues.size();
    }

    public static List<TtRecord> list(int n) {
        return records.tail(n);
    }

    public static TtRecord find(long recordId) {
        for (TtRecord r : records.snapshot()) {
            if (r != null && r.getRecordId() == recordId) {
                return r;
            }
        }
        return null;
    }

    public static void clear() {
        records = new RingBuffer<>(2000);
    }

    public static void onMethodExit(String ttId, String className, String methodName, String methodDesc,
                                    Object[] parameters, Object returnValue, long startTime, long duration) {
        publish(ttId, className, methodName, methodDesc, parameters, returnValue, null, startTime, duration, false);
    }

    public static void onMethodException(String ttId, String className, String methodName, String methodDesc,
                                         Object[] parameters, Throwable exception, long startTime, long duration) {
        publish(ttId, className, methodName, methodDesc, parameters, null, exception, startTime, duration, true);
    }

    private static void publish(String ttId, String className, String methodName, String methodDesc,
                                Object[] parameters, Object returnValue, Throwable exception,
                                long startTime, long duration, boolean isException) {
        if (!enabled) {
            return;
        }
        BlockingQueue<TtRecord> queue = ttQueues.get(ttId);
        if (queue == null) {
            return;
        }
        if (BootstrapMonitorConfig.isWatchDropOnFull() && queue.remainingCapacity() <= 0) {
            dropped.incrementAndGet();
            return;
        }

        try {
            TtRecord r = new TtRecord();
            r.setRecordId(recordSeq.getAndIncrement());
            r.setTtId(ttId);
            r.setClassName(className);
            r.setMethodName(methodName);
            r.setMethodDescriptor(methodDesc);
            r.setParameters(SleuthValueSnapshotter.snapshotParameters(parameters, SNAPSHOT_OPTIONS));
            r.setReturnValue(SleuthValueSnapshotter.snapshotValue(returnValue, SNAPSHOT_OPTIONS));
            r.setException(SleuthValueSnapshotter.snapshotThrowable(exception, SNAPSHOT_OPTIONS));
            r.setStartTime(startTime);
            r.setDuration(duration);
            r.setTimestampMs(System.currentTimeMillis());
            r.setEventType(isException ? TtRecord.EventType.METHOD_EXCEPTION : TtRecord.EventType.METHOD_EXIT);
            r.setThreadName(Thread.currentThread().getName());
            r.setThreadId(Thread.currentThread().getId());

            records.add(r);
            offerWithPolicy(queue, r);
        } catch (Exception e) {
            // ignore
        }
    }

    private static void offerWithPolicy(BlockingQueue<TtRecord> queue, TtRecord record) {
        boolean offered = queue.offer(record);
        if (offered) {
            published.incrementAndGet();
            return;
        }

        if (BootstrapMonitorConfig.isWatchDropOnFull()) {
            dropped.incrementAndGet();
            return;
        }

        queue.poll();
        evicted.incrementAndGet();
        boolean offered2 = queue.offer(record);
        if (offered2) {
            published.incrementAndGet();
        } else {
            dropped.incrementAndGet();
        }
    }

    public static long getPublishedCount() { return published.get(); }
    public static long getDroppedCount() { return dropped.get(); }
    public static long getEvictedCount() { return evicted.get(); }
}
