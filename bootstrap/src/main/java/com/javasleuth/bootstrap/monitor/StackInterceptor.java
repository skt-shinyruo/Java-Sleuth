package com.javasleuth.bootstrap.monitor;

import com.javasleuth.bootstrap.data.StackTraceResult;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * StackInterceptor 用于在方法触发点采集调用栈（简化版）。
 *
 * <p>注意：该逻辑运行在业务线程中，必须保证“失败不影响业务”。</p>
 */
public final class StackInterceptor {
    private static final ConcurrentHashMap<String, BlockingQueue<StackTraceResult>> queues = new ConcurrentHashMap<>();
    private static volatile boolean enabled = false;

    private static final AtomicLong publishedEvents = new AtomicLong(0);
    private static final AtomicLong droppedEvents = new AtomicLong(0);
    private static final AtomicLong evictedEvents = new AtomicLong(0);

    private StackInterceptor() {}

    public static void register(String stackId, BlockingQueue<StackTraceResult> queue) {
        if (stackId == null || stackId.isEmpty() || queue == null) {
            return;
        }
        queues.put(stackId, queue);
        enabled = true;
    }

    public static void unregister(String stackId) {
        if (stackId == null) {
            return;
        }
        queues.remove(stackId);
        enabled = !queues.isEmpty();
    }

    public static void unregisterAll() {
        queues.clear();
        enabled = false;
    }

    /**
     * detach/shutdown 时使用的重置入口（best-effort）。
     *
     * <p>目标：避免跨 attach 统计/缓存残留导致观测与行为漂移。</p>
     */
    public static void resetForDetach() {
        try {
            unregisterAll();
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

    public static boolean isEnabled() {
        return enabled;
    }

    public static int getActiveStackCount() {
        return queues.size();
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

    public static void onMethodEnter(String stackId, String className, String methodName, String methodDesc, int maxDepth) {
        if (!enabled) {
            return;
        }
        if (stackId == null) {
            return;
        }
        BlockingQueue<StackTraceResult> q = queues.get(stackId);
        if (q == null) {
            return;
        }
        if (BootstrapMonitorConfig.isWatchDropOnFull() && q.remainingCapacity() <= 0) {
            droppedEvents.incrementAndGet();
            return;
        }

        try {
            StackTraceElement[] raw = Thread.currentThread().getStackTrace();
            StackTraceElement[] trimmed = trimStack(raw, className, methodName, maxDepth);

            StackTraceResult r = new StackTraceResult();
            r.setStackId(stackId);
            r.setClassName(className);
            r.setMethodName(methodName);
            r.setMethodDescriptor(methodDesc);
            r.setTimestampMs(System.currentTimeMillis());
            r.setEventType(StackTraceResult.EventType.METHOD_ENTRY);
            r.setThreadName(Thread.currentThread().getName());
            r.setThreadId(Thread.currentThread().getId());
            r.setStackTrace(trimmed);

            offerWithPolicy(q, r);
        } catch (Throwable ignored) {
            // 保护业务线程：任何采集异常都应静默忽略
        }
    }

    private static StackTraceElement[] trimStack(StackTraceElement[] raw,
                                                String targetClassName,
                                                String targetMethodName,
                                                int maxDepth) {
        if (raw == null || raw.length == 0) {
            return new StackTraceElement[0];
        }

        int depth = maxDepth;
        if (depth <= 0) {
            depth = 20;
        }
        // 防御性上限：避免用户传入过大导致过多内存复制/输出
        depth = Math.min(depth, 200);

        int start = -1;
        if (targetClassName != null && targetMethodName != null) {
            for (int i = 0; i < raw.length; i++) {
                StackTraceElement e = raw[i];
                if (e == null) {
                    continue;
                }
                if (targetClassName.equals(e.getClassName()) && targetMethodName.equals(e.getMethodName())) {
                    start = i;
                    break;
                }
            }
        }

        if (start < 0) {
            // 回退策略：跳过 Thread.getStackTrace/拦截器自身等噪音帧
            start = 0;
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
                start = i;
                break;
            }
        }

        int end = Math.min(raw.length, start + depth);
        StackTraceElement[] slice = Arrays.copyOfRange(raw, start, end);

        // 二次过滤：移除潜在的插桩/拦截器内部帧，避免影响可读性
        int realStart = 0;
        for (int i = 0; i < slice.length; i++) {
            StackTraceElement e = slice[i];
            if (e == null) {
                continue;
            }
            String cn = e.getClassName();
            if (cn != null && cn.startsWith("com.javasleuth.")) {
                continue;
            }
            realStart = i;
            break;
        }
        if (realStart == 0) {
            return slice;
        }
        return Arrays.copyOfRange(slice, realStart, slice.length);
    }

    private static void offerWithPolicy(BlockingQueue<StackTraceResult> queue, StackTraceResult result) {
        boolean offered = queue.offer(result);
        if (offered) {
            publishedEvents.incrementAndGet();
            return;
        }

        if (BootstrapMonitorConfig.isWatchDropOnFull()) {
            droppedEvents.incrementAndGet();
            return;
        }

        // 先丢最旧的一条再尝试写入
        queue.poll();
        evictedEvents.incrementAndGet();
        boolean offered2 = queue.offer(result);
        if (offered2) {
            publishedEvents.incrementAndGet();
        } else {
            droppedEvents.incrementAndGet();
        }
    }
}
