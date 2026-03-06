package com.javasleuth.core.spy;

import com.javasleuth.bootstrap.monitor.MonitorInterceptor;
import com.javasleuth.bootstrap.monitor.StackInterceptor;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.monitor.TtInterceptor;
import com.javasleuth.bootstrap.monitor.VmToolInterceptor;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;
import com.javasleuth.bootstrap.spy.SleuthSpyAPI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core-side spy implementation: dispatches unified advice events to per-listener handlers.
 *
 * <p>Key requirements:</p>
 * <ul>
 *   <li>Best-effort: never break business threads</li>
 *   <li>Low overhead: single registry lookup by {@code listenerId}</li>
 *   <li>Detach-safe: registry must be clearable; bootstrap holds the only cross-ClassLoader reference</li>
 *   <li>Compatibility: call legacy bootstrap interceptors when no core listener is registered</li>
 * </ul>
 */
public final class SleuthSpyDispatcher extends SleuthSpyAPI.AbstractSpy {

    private static final String CALL_LEGACY_WHEN_LISTENER_PRESENT_PROPERTY =
        "sleuth.spy.dispatcher.legacy.callWhenListenerPresent";
    /**
     * Compatibility switch:
     * <ul>
     *   <li>{@code false} (default): when a core listener is registered for {@code listenerId}, legacy bootstrap interceptors are skipped</li>
     *   <li>{@code true}: always call legacy bootstrap interceptors (even if a core listener exists)</li>
     * </ul>
     */
    private static final boolean CALL_LEGACY_WHEN_LISTENER_PRESENT =
        Boolean.getBoolean(CALL_LEGACY_WHEN_LISTENER_PRESENT_PROPERTY);

    public enum ListenerKind {
        WATCH,
        TRACE,
        MONITOR,
        STACK,
        TT,
        VMTOOL,
        OTHER
    }

    private static final class Registration {
        final ListenerKind kind;
        final SleuthAdviceListener listener;

        Registration(ListenerKind kind, SleuthAdviceListener listener) {
            this.kind = kind == null ? ListenerKind.OTHER : kind;
            this.listener = listener;
        }
    }

    private final ConcurrentHashMap<String, Registration> listeners = new ConcurrentHashMap<>();
    private final AtomicInteger watchCount = new AtomicInteger(0);
    private final AtomicInteger traceCount = new AtomicInteger(0);
    private final AtomicInteger monitorCount = new AtomicInteger(0);
    private final AtomicInteger stackCount = new AtomicInteger(0);
    private final AtomicInteger ttCount = new AtomicInteger(0);
    private final AtomicInteger vmtoolCount = new AtomicInteger(0);
    private final AtomicInteger otherCount = new AtomicInteger(0);

    public void register(String listenerId, ListenerKind kind, SleuthAdviceListener listener) {
        if (listenerId == null || listenerId.isEmpty()) {
            throw new IllegalArgumentException("listenerId");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener");
        }
        Registration reg = new Registration(kind, listener);
        Registration old = listeners.put(listenerId, reg);
        if (old == null) {
            inc(kind);
            return;
        }
        // Replace (best-effort, keep counts roughly consistent).
        dec(old.kind);
        inc(kind);
    }

    public void unregister(String listenerId) {
        if (listenerId == null) {
            return;
        }
        Registration old = listeners.remove(listenerId);
        if (old != null) {
            dec(old.kind);
        }
    }

    public void clear() {
        listeners.clear();
        watchCount.set(0);
        traceCount.set(0);
        monitorCount.set(0);
        stackCount.set(0);
        ttCount.set(0);
        vmtoolCount.set(0);
        otherCount.set(0);
    }

    public int getActiveListenerCount() {
        return listeners.size();
    }

    public int getActiveWatchCount() {
        return watchCount.get();
    }

    public int getActiveTraceCount() {
        return traceCount.get();
    }

    public int getActiveMonitorCount() {
        return monitorCount.get();
    }

    public int getActiveStackCount() {
        return stackCount.get();
    }

    public int getActiveTtCount() {
        return ttCount.get();
    }

    public int getActiveVmToolCount() {
        return vmtoolCount.get();
    }

    public int getActiveOtherCount() {
        return otherCount.get();
    }

    private void inc(ListenerKind kind) {
        if (kind == ListenerKind.WATCH) {
            watchCount.incrementAndGet();
        } else if (kind == ListenerKind.TRACE) {
            traceCount.incrementAndGet();
        } else if (kind == ListenerKind.MONITOR) {
            monitorCount.incrementAndGet();
        } else if (kind == ListenerKind.STACK) {
            stackCount.incrementAndGet();
        } else if (kind == ListenerKind.TT) {
            ttCount.incrementAndGet();
        } else if (kind == ListenerKind.VMTOOL) {
            vmtoolCount.incrementAndGet();
        } else {
            otherCount.incrementAndGet();
        }
    }

    private void dec(ListenerKind kind) {
        if (kind == ListenerKind.WATCH) {
            watchCount.decrementAndGet();
        } else if (kind == ListenerKind.TRACE) {
            traceCount.decrementAndGet();
        } else if (kind == ListenerKind.MONITOR) {
            monitorCount.decrementAndGet();
        } else if (kind == ListenerKind.STACK) {
            stackCount.decrementAndGet();
        } else if (kind == ListenerKind.TT) {
            ttCount.decrementAndGet();
        } else if (kind == ListenerKind.VMTOOL) {
            vmtoolCount.decrementAndGet();
        } else {
            otherCount.decrementAndGet();
        }
    }

    @Override
    public void atEnter(String listenerId, Class<?> clazz, String methodInfo, Object target, Object[] args, long startNanos) {
        // 1) core listeners
        Registration reg = (listenerId != null) ? listeners.get(listenerId) : null;
        if (reg != null && reg.listener != null) {
            try {
                reg.listener.onEnter(clazz, methodInfo, target, args, startNanos);
            } catch (Throwable ignore) {
                // best-effort
            }
            if (!CALL_LEGACY_WHEN_LISTENER_PRESENT) {
                return;
            }
        }

        // 2) legacy bootstrap interceptors (compat)
        String className = safeClassName(clazz);
        MethodParts mp = MethodParts.parse(methodInfo);
        try {
            WatchInterceptor.onMethodEntry(listenerId, className, mp.methodName, mp.methodDesc, args, startNanos, args != null);
        } catch (Throwable ignore) {
            // best-effort
        }
        try {
            TraceInterceptor.onMethodEntry(listenerId, className, mp.methodName, mp.methodDesc, startNanos);
        } catch (Throwable ignore) {
            // best-effort
        }
        try {
            StackInterceptor.onMethodEnter(listenerId, className, mp.methodName, mp.methodDesc);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    @Override
    public void atExit(
        String listenerId,
        Class<?> clazz,
        String methodInfo,
        Object target,
        Object[] args,
        Object returnObject,
        boolean returnCaptured,
        long startNanos,
        long durationNanos
    ) {
        Registration reg = (listenerId != null) ? listeners.get(listenerId) : null;
        if (reg != null && reg.listener != null) {
            try {
                reg.listener.onExit(clazz, methodInfo, target, args, returnObject, returnCaptured, startNanos, durationNanos);
            } catch (Throwable ignore) {
                // best-effort
            }
            if (!CALL_LEGACY_WHEN_LISTENER_PRESENT) {
                return;
            }
        }

        String className = safeClassName(clazz);
        MethodParts mp = MethodParts.parse(methodInfo);
        try {
            WatchInterceptor.onMethodExit(listenerId, className, mp.methodName, mp.methodDesc, returnObject, startNanos, durationNanos, returnCaptured);
        } catch (Throwable ignore) {
            // best-effort
        }
        try {
            TraceInterceptor.onMethodExit(listenerId, className, mp.methodName, mp.methodDesc, startNanos, durationNanos, false);
        } catch (Throwable ignore) {
            // best-effort
        }
        try {
            MonitorInterceptor.onMethodExit(listenerId, className, mp.methodName, mp.methodDesc, durationNanos, false);
        } catch (Throwable ignore) {
            // best-effort
        }
        try {
            TtInterceptor.onMethodExit(listenerId, className, mp.methodName, mp.methodDesc, args, returnObject, startNanos, durationNanos);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    @Override
    public void atExceptionExit(
        String listenerId,
        Class<?> clazz,
        String methodInfo,
        Object target,
        Object[] args,
        Throwable throwable,
        long startNanos,
        long durationNanos
    ) {
        Registration reg = (listenerId != null) ? listeners.get(listenerId) : null;
        if (reg != null && reg.listener != null) {
            try {
                reg.listener.onExceptionExit(clazz, methodInfo, target, args, throwable, startNanos, durationNanos);
            } catch (Throwable ignore) {
                // best-effort
            }
            if (!CALL_LEGACY_WHEN_LISTENER_PRESENT) {
                return;
            }
        }

        String className = safeClassName(clazz);
        MethodParts mp = MethodParts.parse(methodInfo);
        try {
            WatchInterceptor.onMethodException(listenerId, className, mp.methodName, mp.methodDesc, throwable, startNanos, durationNanos);
        } catch (Throwable ignore) {
            // best-effort
        }
        try {
            TraceInterceptor.onMethodExit(listenerId, className, mp.methodName, mp.methodDesc, startNanos, durationNanos, true);
        } catch (Throwable ignore) {
            // best-effort
        }
        try {
            MonitorInterceptor.onMethodExit(listenerId, className, mp.methodName, mp.methodDesc, durationNanos, true);
        } catch (Throwable ignore) {
            // best-effort
        }
        try {
            TtInterceptor.onMethodException(listenerId, className, mp.methodName, mp.methodDesc, args, throwable, startNanos, durationNanos);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    @Override
    public void atBeforeInvoke(String listenerId, Class<?> clazz, String invokeInfo, Object target, long whenNanos) {
        Registration reg = (listenerId != null) ? listeners.get(listenerId) : null;
        if (reg != null && reg.listener != null) {
            try {
                reg.listener.onBeforeInvoke(clazz, invokeInfo, target, whenNanos);
            } catch (Throwable ignore) {
                // best-effort
            }
            if (!CALL_LEGACY_WHEN_LISTENER_PRESENT) {
                return;
            }
        }

        // legacy trace: only emits SUB_METHOD_CALL at before-invoke
        try {
            InvokeParts ip = InvokeParts.parse(invokeInfo);
            String ownerBinary = ip.ownerInternal.replace('/', '.');
            TraceInterceptor.onSubMethodCall(listenerId, ownerBinary, ip.methodName, ip.methodDesc, whenNanos);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    @Override
    public void atAfterInvoke(String listenerId, Class<?> clazz, String invokeInfo, Object target, long whenNanos) {
        Registration reg = (listenerId != null) ? listeners.get(listenerId) : null;
        if (reg != null && reg.listener != null) {
            try {
                reg.listener.onAfterInvoke(clazz, invokeInfo, target, whenNanos);
            } catch (Throwable ignore) {
                // best-effort
            }
        }
    }

    @Override
    public void atInvokeException(String listenerId, Class<?> clazz, String invokeInfo, Object target, Throwable throwable, long whenNanos) {
        Registration reg = (listenerId != null) ? listeners.get(listenerId) : null;
        if (reg != null && reg.listener != null) {
            try {
                reg.listener.onInvokeException(clazz, invokeInfo, target, throwable, whenNanos);
            } catch (Throwable ignore) {
                // best-effort
            }
        }
    }

    @Override
    public void onConstructed(String listenerId, Object instance) {
        Registration reg = (listenerId != null) ? listeners.get(listenerId) : null;
        if (reg != null && reg.listener != null) {
            try {
                reg.listener.onConstructed(instance);
            } catch (Throwable ignore) {
                // best-effort
            }
            if (!CALL_LEGACY_WHEN_LISTENER_PRESENT) {
                return;
            }
        }
        try {
            VmToolInterceptor.onConstructed(listenerId, instance);
        } catch (Throwable ignore) {
            // best-effort
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
        final String ownerInternal;
        final String methodName;
        final String methodDesc;
        final int line;

        private InvokeParts(String ownerInternal, String methodName, String methodDesc, int line) {
            this.ownerInternal = ownerInternal;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
            this.line = line;
        }

        static InvokeParts parse(String invokeInfo) {
            if (invokeInfo == null) {
                return new InvokeParts("?", "?", "", -1);
            }
            int i1 = invokeInfo.indexOf('|');
            if (i1 < 0) {
                return new InvokeParts(invokeInfo, "?", "", -1);
            }
            int i2 = invokeInfo.indexOf('|', i1 + 1);
            if (i2 < 0) {
                return new InvokeParts(invokeInfo.substring(0, i1), invokeInfo.substring(i1 + 1), "", -1);
            }
            int i3 = invokeInfo.indexOf('|', i2 + 1);
            if (i3 < 0) {
                return new InvokeParts(invokeInfo.substring(0, i1), invokeInfo.substring(i1 + 1, i2), invokeInfo.substring(i2 + 1), -1);
            }

            String owner = invokeInfo.substring(0, i1);
            String name = invokeInfo.substring(i1 + 1, i2);
            String desc = invokeInfo.substring(i2 + 1, i3);
            int line = -1;
            try {
                line = Integer.parseInt(invokeInfo.substring(i3 + 1));
            } catch (Throwable ignore) {
                line = -1;
            }
            return new InvokeParts(owner, name, desc, line);
        }
    }
}
