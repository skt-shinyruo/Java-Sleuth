package com.javasleuth.core.spy;

import com.javasleuth.bootstrap.spy.SleuthSpyAPI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core-side spy implementation: dispatches unified advice events to per-listener handlers.
 *
 * <p>Key requirements: best-effort, detach-safe, and allocation-minimal.</p>
 */
public final class SleuthSpyDispatcher extends SleuthSpyAPI.AbstractSpy {

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

    public static boolean isInstalled(SleuthSpyDispatcher dispatcher) {
        if (dispatcher == null) {
            return false;
        }
        try {
            if (!SleuthSpyAPI.isInited()) {
                return false;
            }
            return SleuthSpyAPI.getSpy() == dispatcher;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String unavailableMessage(String feature) {
        String name = feature == null || feature.trim().isEmpty() ? "listener-based diagnostics" : feature.trim();
        return name + " requires an installed SleuthSpyDispatcher listener runtime. "
            + "SleuthSpyDispatcher is not installed.";
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
        Registration reg = (listenerId != null) ? listeners.get(listenerId) : null;
        if (reg != null && reg.listener != null) {
            try {
                reg.listener.onEnter(clazz, methodInfo, target, args, startNanos);
            } catch (Throwable ignore) {
                // best-effort
            }
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
        }
    }
}
