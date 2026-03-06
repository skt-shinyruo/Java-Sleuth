package com.javasleuth.core.spy.listener;

import com.javasleuth.bootstrap.data.WatchResult;
import com.javasleuth.bootstrap.util.SleuthValueFormatter;
import com.javasleuth.bootstrap.util.SleuthValueSnapshotter;
import com.javasleuth.core.spy.SleuthAdviceListener;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core-side implementation of watch logic as a listener (no bootstrap interceptor registry required).
 */
public final class WatchAdviceListener implements SleuthAdviceListener {
    private final String watchId;
    private final BlockingQueue<WatchResult> queue;
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

    public WatchAdviceListener(String watchId, BlockingQueue<WatchResult> queue, boolean dropOnFull) {
        if (watchId == null || watchId.isEmpty()) {
            throw new IllegalArgumentException("watchId");
        }
        if (queue == null) {
            throw new IllegalArgumentException("queue");
        }
        this.watchId = watchId;
        this.queue = queue;
        this.dropOnFull = dropOnFull;
    }

    @Override
    public void onEnter(Class<?> clazz, String methodInfo, Object target, Object[] args, long startNanos) {
        try {
            if (dropOnFull && queue.remainingCapacity() <= 0) {
                droppedEvents.incrementAndGet();
                return;
            }

            MethodParts mp = MethodParts.parse(methodInfo);

            WatchResult r = new WatchResult();
            r.setWatchId(watchId);
            r.setClassName(safeClassName(clazz));
            r.setMethodName(mp.methodName);
            r.setMethodDescriptor(mp.methodDesc);
            r.setParametersCaptured(args != null);
            if (args != null) {
                r.setParameters(SleuthValueSnapshotter.snapshotParameters(args, SNAPSHOT_OPTIONS));
            } else {
                r.setParameters(null);
            }
            r.setStartTime(startNanos);
            r.setEventType(WatchResult.EventType.METHOD_ENTRY);
            Thread t = Thread.currentThread();
            r.setThreadName(t.getName());
            r.setThreadId(t.getId());

            offerWithPolicy(r);
        } catch (Throwable ignore) {
            // best-effort
        }
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
        try {
            if (dropOnFull && queue.remainingCapacity() <= 0) {
                droppedEvents.incrementAndGet();
                return;
            }

            MethodParts mp = MethodParts.parse(methodInfo);

            WatchResult r = new WatchResult();
            r.setWatchId(watchId);
            r.setClassName(safeClassName(clazz));
            r.setMethodName(mp.methodName);
            r.setMethodDescriptor(mp.methodDesc);
            r.setReturnCaptured(returnCaptured);
            if (returnCaptured) {
                r.setReturnValue(SleuthValueSnapshotter.snapshotValue(returnObject, SNAPSHOT_OPTIONS));
            } else {
                r.setReturnValue(null);
            }
            r.setStartTime(startNanos);
            r.setDuration(durationNanos);
            r.setEventType(WatchResult.EventType.METHOD_EXIT);
            Thread t = Thread.currentThread();
            r.setThreadName(t.getName());
            r.setThreadId(t.getId());

            offerWithPolicy(r);
        } catch (Throwable ignore) {
            // best-effort
        }
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
        try {
            if (dropOnFull && queue.remainingCapacity() <= 0) {
                droppedEvents.incrementAndGet();
                return;
            }

            MethodParts mp = MethodParts.parse(methodInfo);

            WatchResult r = new WatchResult();
            r.setWatchId(watchId);
            r.setClassName(safeClassName(clazz));
            r.setMethodName(mp.methodName);
            r.setMethodDescriptor(mp.methodDesc);
            r.setException(SleuthValueSnapshotter.snapshotThrowable(throwable, SNAPSHOT_OPTIONS));
            r.setStartTime(startNanos);
            r.setDuration(durationNanos);
            r.setEventType(WatchResult.EventType.METHOD_EXCEPTION);
            Thread t = Thread.currentThread();
            r.setThreadName(t.getName());
            r.setThreadId(t.getId());

            offerWithPolicy(r);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private void offerWithPolicy(WatchResult result) {
        boolean offered = queue.offer(result);
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
        boolean offered2 = queue.offer(result);
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

