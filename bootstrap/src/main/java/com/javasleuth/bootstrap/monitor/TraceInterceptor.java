package com.javasleuth.bootstrap.monitor;

import com.javasleuth.bootstrap.data.TraceResult;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class TraceInterceptor {
    private static final ConcurrentHashMap<String, BlockingQueue<TraceResult>> traceQueues = new ConcurrentHashMap<>();
    private static volatile boolean enabled = false;
    private static final AtomicLong publishedEvents = new AtomicLong(0);
    private static final AtomicLong droppedEvents = new AtomicLong(0);
    private static final AtomicLong evictedEvents = new AtomicLong(0);
    // detach → re-attach 的代际（epoch）。用于在下次命中拦截器时清理 ThreadLocal 残留。
    private static final AtomicLong EPOCH = new AtomicLong(0);

    private static final class PerTraceState {
        // Invocation nesting stack. We only use size/push/pop for depth tracking.
        final ArrayDeque<Boolean> callStack = new ArrayDeque<>();
    }

    private static final class ThreadState {
        long epoch;
        final Map<String, PerTraceState> traces = new HashMap<>();
    }

    private static final ThreadLocal<ThreadState> perThreadState =
        ThreadLocal.withInitial(() -> {
            ThreadState ts = new ThreadState();
            ts.epoch = EPOCH.get();
            return ts;
        });

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

    /**
     * detach/shutdown 时使用的重置入口（best-effort）。
     *
     * <p>目标：</p>
     * <ul>
     *   <li>清理队列/覆盖配置与统计计数，避免跨 attach 漂移</li>
     *   <li>推进 epoch，使得各线程的 ThreadLocal 状态在下次命中时自动清空</li>
     * </ul>
     */
    public static void resetForDetach() {
        try {
            unregisterAllTraces();
        } catch (Exception ignore) {
            // ignore
        }
        try {
            EPOCH.incrementAndGet();
            // best-effort：清理当前线程的 ThreadLocal；其他线程在下次命中时会自动清理。
            perThreadState.remove();
        } catch (Exception ignore) {
            // ignore
        }
        resetMetrics();
    }

    /**
     * 重置统计计数（不会影响功能开关）。
     */
    public static void resetMetrics() {
        try {
            publishedEvents.set(0);
            droppedEvents.set(0);
            evictedEvents.set(0);
        } catch (Exception ignore) {
            // ignore
        }
    }

    public static void onMethodEntry(String traceId, String className, String methodName,
                                   String methodDesc, long startTime) {
        if (!enabled) return;

        BlockingQueue<TraceResult> queue = traceQueues.get(traceId);
        if (queue == null) return;

        try {
            PerTraceState state = getOrCreateState(traceId);
            int depth = state.callStack.size();
            state.callStack.addFirst(Boolean.TRUE);

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
            Boolean marker = state.callStack.pollFirst();
            if (marker == null) {
                return;
            }
            if (state.callStack.isEmpty()) {
                removeState(traceId);
            }

            BlockingQueue<TraceResult> queue = traceQueues.get(traceId);
            if (queue == null) {
                return;
            }
            int depth = state.callStack.size();

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
            if (state.callStack.isEmpty()) {
                return;
            }
            int depth = state.callStack.size();

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

    private static PerTraceState getOrCreateState(String traceId) {
        ThreadState ts = currentThreadState();
        PerTraceState state = ts.traces.get(traceId);
        if (state == null) {
            state = new PerTraceState();
            ts.traces.put(traceId, state);
        }
        return state;
    }

    private static PerTraceState getState(String traceId) {
        return currentThreadState().traces.get(traceId);
    }

    private static void removeState(String traceId) {
        ThreadState ts = currentThreadState();
        ts.traces.remove(traceId);
        if (ts.traces.isEmpty()) {
            perThreadState.remove();
        }
    }

    private static ThreadState currentThreadState() {
        ThreadState ts = perThreadState.get();
        long cur = EPOCH.get();
        if (ts.epoch != cur) {
            ts.traces.clear();
            ts.epoch = cur;
        }
        return ts;
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
}
