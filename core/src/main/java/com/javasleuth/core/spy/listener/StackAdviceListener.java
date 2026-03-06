package com.javasleuth.core.spy.listener;

import com.javasleuth.bootstrap.data.StackTraceResult;
import com.javasleuth.core.spy.SleuthAdviceListener;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core-side implementation of stack trace capture as a listener.
 *
 * <p>This avoids the bootstrap {@code StackInterceptor} registry so detach does not rely on clearing
 * bootstrap static maps to release per-session state.</p>
 */
public final class StackAdviceListener implements SleuthAdviceListener {
    private static final int DEFAULT_MAX_DEPTH = 20;

    private final String stackId;
    private final BlockingQueue<StackTraceResult> queue;
    private final int maxDepth;
    private final boolean dropOnFull;

    private final AtomicLong publishedEvents = new AtomicLong(0);
    private final AtomicLong droppedEvents = new AtomicLong(0);
    private final AtomicLong evictedEvents = new AtomicLong(0);

    public StackAdviceListener(String stackId, BlockingQueue<StackTraceResult> queue, int maxDepth, boolean dropOnFull) {
        if (stackId == null || stackId.isEmpty()) {
            throw new IllegalArgumentException("stackId");
        }
        if (queue == null) {
            throw new IllegalArgumentException("queue");
        }
        this.stackId = stackId;
        this.queue = queue;
        this.maxDepth = normalizeDepth(maxDepth);
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
            String className = safeClassName(clazz);

            StackTraceElement[] raw = Thread.currentThread().getStackTrace();
            StackTraceElement[] trimmed = trimStack(raw, className, mp.methodName, maxDepth);

            StackTraceResult r = new StackTraceResult();
            r.setStackId(stackId);
            r.setClassName(className);
            r.setMethodName(mp.methodName);
            r.setMethodDescriptor(mp.methodDesc);
            r.setTimestampMs(System.currentTimeMillis());
            r.setEventType(StackTraceResult.EventType.METHOD_ENTRY);
            Thread t = Thread.currentThread();
            r.setThreadName(t.getName());
            r.setThreadId(t.getId());
            r.setStackTrace(trimmed);

            offerWithPolicy(r);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private void offerWithPolicy(StackTraceResult result) {
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

    private static int normalizeDepth(int maxDepth) {
        int d = maxDepth <= 0 ? DEFAULT_MAX_DEPTH : maxDepth;
        // Defensive upper bound to avoid large array copies.
        return Math.min(d, 200);
    }

    private static StackTraceElement[] trimStack(StackTraceElement[] raw, String targetClassName, String targetMethodName, int maxDepth) {
        if (raw == null || raw.length == 0) {
            return new StackTraceElement[0];
        }

        int start = findTargetFrame(raw, targetClassName, targetMethodName);
        if (start < 0) {
            start = findFirstUsefulFrame(raw);
        }

        int end = Math.min(raw.length, start + normalizeDepth(maxDepth));
        StackTraceElement[] slice = Arrays.copyOfRange(raw, start, end);

        // Trim leading agent frames for readability (keep target frame if it happens to be inside com.javasleuth.*).
        int realStart = 0;
        for (int i = 0; i < slice.length; i++) {
            StackTraceElement e = slice[i];
            if (e == null) {
                continue;
            }
            String cn = e.getClassName();
            if (cn == null) {
                continue;
            }
            if (cn.startsWith("com.javasleuth.") && (targetClassName == null || !cn.equals(targetClassName))) {
                continue;
            }
            realStart = i;
            break;
        }
        if (realStart <= 0) {
            return slice;
        }
        return Arrays.copyOfRange(slice, realStart, slice.length);
    }

    private static int findTargetFrame(StackTraceElement[] raw, String targetClassName, String targetMethodName) {
        if (targetClassName == null || targetMethodName == null) {
            return -1;
        }
        for (int i = 0; i < raw.length; i++) {
            StackTraceElement e = raw[i];
            if (e == null) {
                continue;
            }
            if (targetClassName.equals(e.getClassName()) && targetMethodName.equals(e.getMethodName())) {
                return i;
            }
        }
        return -1;
    }

    private static int findFirstUsefulFrame(StackTraceElement[] raw) {
        for (int i = 0; i < raw.length; i++) {
            StackTraceElement e = raw[i];
            if (e == null) {
                continue;
            }
            String cn = e.getClassName();
            if (cn == null) {
                continue;
            }
            if (cn.startsWith("java.lang.Thread")) {
                continue;
            }
            if (cn.startsWith("com.javasleuth.")) {
                continue;
            }
            return i;
        }
        return 0;
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

