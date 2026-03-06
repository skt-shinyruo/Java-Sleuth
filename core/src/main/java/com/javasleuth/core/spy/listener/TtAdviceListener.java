package com.javasleuth.core.spy.listener;

import com.javasleuth.bootstrap.data.TtRecord;
import com.javasleuth.bootstrap.util.SleuthValueFormatter;
import com.javasleuth.bootstrap.util.SleuthValueSnapshotter;
import com.javasleuth.core.command.impl.tt.TtRecordStore;
import com.javasleuth.core.spy.SleuthAdviceListener;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core-side TT listener: records params/return/exception and streams to a per-session queue.
 */
public final class TtAdviceListener implements SleuthAdviceListener {
    private final String ttId;
    private final BlockingQueue<TtRecord> queue;
    private final TtRecordStore recordStore;
    private final boolean dropOnFull;

    private final AtomicLong publishedEvents = new AtomicLong(0);
    private final AtomicLong droppedEvents = new AtomicLong(0);
    private final AtomicLong evictedEvents = new AtomicLong(0);

    private static final SleuthValueFormatter.Options SNAPSHOT_OPTIONS =
        new SleuthValueFormatter.Options()
            .withMaxDepth(2)
            .withMaxStringLength(200)
            .withMaxCollectionItems(20)
            .withMaxMapEntries(20);

    public TtAdviceListener(String ttId, BlockingQueue<TtRecord> queue, TtRecordStore recordStore, boolean dropOnFull) {
        if (ttId == null || ttId.isEmpty()) {
            throw new IllegalArgumentException("ttId");
        }
        if (queue == null) {
            throw new IllegalArgumentException("queue");
        }
        if (recordStore == null) {
            throw new IllegalArgumentException("recordStore");
        }
        this.ttId = ttId;
        this.queue = queue;
        this.recordStore = recordStore;
        this.dropOnFull = dropOnFull;
    }

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
        publish(clazz, methodInfo, args, returnObject, null, startNanos, durationNanos, false);
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
        publish(clazz, methodInfo, args, null, throwable, startNanos, durationNanos, true);
    }

    private void publish(
        Class<?> clazz,
        String methodInfo,
        Object[] parameters,
        Object returnValue,
        Throwable exception,
        long startNanos,
        long durationNanos,
        boolean isException
    ) {
        try {
            if (dropOnFull && queue.remainingCapacity() <= 0) {
                droppedEvents.incrementAndGet();
                return;
            }

            MethodParts mp = MethodParts.parse(methodInfo);
            Thread t = Thread.currentThread();

            TtRecord r = new TtRecord();
            r.setRecordId(recordStore.nextRecordId());
            r.setTtId(ttId);
            r.setClassName(safeClassName(clazz));
            r.setMethodName(mp.methodName);
            r.setMethodDescriptor(mp.methodDesc);
            r.setParameters(SleuthValueSnapshotter.snapshotParameters(parameters, SNAPSHOT_OPTIONS));
            r.setReturnValue(SleuthValueSnapshotter.snapshotValue(returnValue, SNAPSHOT_OPTIONS));
            r.setException(SleuthValueSnapshotter.snapshotThrowable(exception, SNAPSHOT_OPTIONS));
            r.setStartTime(startNanos);
            r.setDuration(durationNanos);
            r.setTimestampMs(System.currentTimeMillis());
            r.setEventType(isException ? TtRecord.EventType.METHOD_EXCEPTION : TtRecord.EventType.METHOD_EXIT);
            r.setThreadName(t.getName());
            r.setThreadId(t.getId());

            recordStore.add(r);
            offerWithPolicy(r);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private void offerWithPolicy(TtRecord record) {
        boolean offered = queue.offer(record);
        if (offered) {
            publishedEvents.incrementAndGet();
            return;
        }

        if (dropOnFull) {
            droppedEvents.incrementAndGet();
            return;
        }

        queue.poll();
        evictedEvents.incrementAndGet();
        boolean offered2 = queue.offer(record);
        if (offered2) {
            publishedEvents.incrementAndGet();
        } else {
            droppedEvents.incrementAndGet();
        }
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

