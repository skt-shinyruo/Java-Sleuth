package com.javasleuth.core.spy.listener;

import com.javasleuth.bootstrap.data.TraceResult;
import com.javasleuth.core.spy.SleuthAdviceListener;
import java.util.ArrayDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core-side implementation of trace logic as a listener.
 *
 * <p>Unlike the legacy bootstrap {@code TraceInterceptor}, this listener pairs invoke before/after/exception
 * to compute sub-call duration.</p>
 */
public final class TraceAdviceListener implements SleuthAdviceListener {
    private final String traceId;
    private final BlockingQueue<TraceResult> queue;
    private final boolean dropOnFull;

    private final AtomicLong publishedEvents = new AtomicLong(0);
    private final AtomicLong droppedEvents = new AtomicLong(0);
    private final AtomicLong evictedEvents = new AtomicLong(0);

    /**
     * Per-thread state keyed by thread id.
     *
     * <p>We intentionally avoid {@code ThreadLocal} here to reduce detach/unload risks when spy is set to NOP.</p>
     */
    private final ConcurrentHashMap<Long, ThreadState> states = new ConcurrentHashMap<>();

    private static final class ThreadState {
        int methodDepth = 0;
        ArrayDeque<InvokeFrame> invokeStack;
    }

    private static final class InvokeFrame {
        final String ownerBinary;
        final String methodName;
        final String methodDesc;
        final long startNanos;
        final int depth;

        InvokeFrame(String ownerBinary, String methodName, String methodDesc, long startNanos, int depth) {
            this.ownerBinary = ownerBinary;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
            this.startNanos = startNanos;
            this.depth = depth;
        }
    }

    public TraceAdviceListener(String traceId, BlockingQueue<TraceResult> queue, boolean dropOnFull) {
        if (traceId == null || traceId.isEmpty()) {
            throw new IllegalArgumentException("traceId");
        }
        if (queue == null) {
            throw new IllegalArgumentException("queue");
        }
        this.traceId = traceId;
        this.queue = queue;
        this.dropOnFull = dropOnFull;
    }

    @Override
    public void onEnter(Class<?> clazz, String methodInfo, Object target, Object[] args, long startNanos) {
        try {
            long tid = Thread.currentThread().getId();
            ThreadState ts = getOrCreateState(tid);
            int depth = ts.methodDepth;
            ts.methodDepth++;

            MethodParts mp = MethodParts.parse(methodInfo);

            TraceResult r = new TraceResult();
            r.setTraceId(traceId);
            r.setClassName(safeClassName(clazz));
            r.setMethodName(mp.methodName);
            r.setMethodDescriptor(mp.methodDesc);
            r.setStartTime(startNanos);
            r.setEventType(TraceResult.EventType.METHOD_ENTRY);
            r.setDepth(depth);
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
        onExit0(clazz, methodInfo, startNanos, durationNanos, false);
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
        onExit0(clazz, methodInfo, startNanos, durationNanos, true);
    }

    private void onExit0(Class<?> clazz, String methodInfo, long startNanos, long durationNanos, boolean exception) {
        try {
            Thread t = Thread.currentThread();
            long tid = t.getId();
            ThreadState ts = states.get(tid);
            if (ts == null || ts.methodDepth <= 0) {
                return;
            }
            ts.methodDepth--;
            int depth = ts.methodDepth;
            if (ts.methodDepth == 0) {
                // Avoid state accumulation for long-lived threads.
                states.remove(tid);
            }

            MethodParts mp = MethodParts.parse(methodInfo);

            TraceResult r = new TraceResult();
            r.setTraceId(traceId);
            r.setClassName(safeClassName(clazz));
            r.setMethodName(mp.methodName);
            r.setMethodDescriptor(mp.methodDesc);
            r.setStartTime(startNanos);
            r.setDuration(durationNanos);
            r.setEventType(exception ? TraceResult.EventType.METHOD_EXCEPTION : TraceResult.EventType.METHOD_EXIT);
            r.setDepth(depth);
            r.setThreadName(t.getName());
            r.setThreadId(tid);

            offerWithPolicy(r);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    @Override
    public void onBeforeInvoke(Class<?> clazz, String invokeInfo, Object target, long whenNanos) {
        try {
            long tid = Thread.currentThread().getId();
            ThreadState ts = states.get(tid);
            if (ts == null || ts.methodDepth <= 0) {
                return;
            }

            InvokeParts ip = InvokeParts.parse(invokeInfo);
            if (ip == null) {
                return;
            }

            if (ts.invokeStack == null) {
                ts.invokeStack = new ArrayDeque<>();
            }
            ts.invokeStack.addFirst(new InvokeFrame(ip.ownerBinary, ip.methodName, ip.methodDesc, whenNanos, ts.methodDepth));
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    @Override
    public void onAfterInvoke(Class<?> clazz, String invokeInfo, Object target, long whenNanos) {
        onInvokeFinish(whenNanos);
    }

    @Override
    public void onInvokeException(Class<?> clazz, String invokeInfo, Object target, Throwable throwable, long whenNanos) {
        onInvokeFinish(whenNanos);
    }

    private void onInvokeFinish(long whenNanos) {
        try {
            Thread t = Thread.currentThread();
            long tid = t.getId();
            ThreadState ts = states.get(tid);
            if (ts == null || ts.methodDepth <= 0) {
                return;
            }
            ArrayDeque<InvokeFrame> stack = ts.invokeStack;
            if (stack == null) {
                return;
            }
            InvokeFrame f = stack.pollFirst();
            if (f == null) {
                return;
            }
            long duration = whenNanos - f.startNanos;
            if (duration < 0) {
                duration = 0;
            }

            TraceResult r = new TraceResult();
            r.setTraceId(traceId);
            r.setClassName(f.ownerBinary);
            r.setMethodName(f.methodName);
            r.setMethodDescriptor(f.methodDesc);
            r.setStartTime(f.startNanos);
            r.setDuration(duration);
            r.setEventType(TraceResult.EventType.SUB_METHOD_CALL);
            r.setDepth(f.depth);
            r.setThreadName(t.getName());
            r.setThreadId(tid);

            offerWithPolicy(r);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private ThreadState getOrCreateState(long tid) {
        ThreadState ts = states.get(tid);
        if (ts != null) {
            return ts;
        }
        ThreadState fresh = new ThreadState();
        ThreadState prev = states.putIfAbsent(tid, fresh);
        return prev != null ? prev : fresh;
    }

    private void offerWithPolicy(TraceResult result) {
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

    private static final class InvokeParts {
        final String ownerBinary;
        final String methodName;
        final String methodDesc;

        private InvokeParts(String ownerBinary, String methodName, String methodDesc) {
            this.ownerBinary = ownerBinary;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        static InvokeParts parse(String invokeInfo) {
            if (invokeInfo == null) {
                return null;
            }
            int i1 = invokeInfo.indexOf('|');
            if (i1 < 0) {
                return null;
            }
            int i2 = invokeInfo.indexOf('|', i1 + 1);
            if (i2 < 0) {
                return null;
            }
            int i3 = invokeInfo.indexOf('|', i2 + 1);
            if (i3 < 0) {
                return null;
            }
            String ownerInternal = invokeInfo.substring(0, i1);
            String name = invokeInfo.substring(i1 + 1, i2);
            String desc = invokeInfo.substring(i2 + 1, i3);
            String ownerBinary = ownerInternal.replace('/', '.');
            return new InvokeParts(ownerBinary, name, desc);
        }
    }
}

