package com.javasleuth.bootstrap.spy;

import com.javasleuth.bootstrap.monitor.MonitorInterceptor;
import com.javasleuth.bootstrap.monitor.StackInterceptor;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.monitor.TtInterceptor;
import com.javasleuth.bootstrap.monitor.VmToolInterceptor;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;

/**
 * Bootstrap-visible Spy API (SSOT) for enhanced bytecode callbacks.
 *
 * <p>Design goals:</p>
 * <ul>
 *   <li>Single stable entrypoint for bytecode injections (reduces scattered owner strings)</li>
 *   <li>JDK-only (bootstrap-friendly)</li>
 *   <li>Supports both method events and invoke events (for trace-like features)</li>
 * </ul>
 *
 * <p>Note: The default implementation delegates to existing bootstrap interceptors for backward compatibility.
 * A future refactor may replace the spy instance with a listener/advice dispatcher.</p>
 */
public final class SleuthSpyAPI {
    public static final AbstractSpy NOP_SPY = new NopSpy();

    private static volatile AbstractSpy spyInstance = new LegacyBootstrapSpy();
    private static volatile boolean inited = false;

    private SleuthSpyAPI() {}

    public static AbstractSpy getSpy() {
        return spyInstance;
    }

    public static void setSpy(AbstractSpy spy) {
        spyInstance = (spy != null) ? spy : NOP_SPY;
    }

    public static void setNopSpy() {
        spyInstance = NOP_SPY;
    }

    public static boolean isNopSpy() {
        return spyInstance == NOP_SPY;
    }

    public static void init() {
        inited = true;
    }

    public static boolean isInited() {
        return inited;
    }

    public static void destroy() {
        setNopSpy();
        inited = false;
    }

    public static void atEnter(
        String listenerId,
        Class<?> clazz,
        String methodInfo,
        Object target,
        Object[] args,
        long startNanos
    ) {
        spyInstance.atEnter(listenerId, clazz, methodInfo, target, args, startNanos);
    }

    public static void atExit(
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
        spyInstance.atExit(listenerId, clazz, methodInfo, target, args, returnObject, returnCaptured, startNanos, durationNanos);
    }

    public static void atExceptionExit(
        String listenerId,
        Class<?> clazz,
        String methodInfo,
        Object target,
        Object[] args,
        Throwable throwable,
        long startNanos,
        long durationNanos
    ) {
        spyInstance.atExceptionExit(listenerId, clazz, methodInfo, target, args, throwable, startNanos, durationNanos);
    }

    public static void atBeforeInvoke(
        String listenerId,
        Class<?> clazz,
        String invokeInfo,
        Object target,
        long whenNanos
    ) {
        spyInstance.atBeforeInvoke(listenerId, clazz, invokeInfo, target, whenNanos);
    }

    public static void atAfterInvoke(
        String listenerId,
        Class<?> clazz,
        String invokeInfo,
        Object target,
        long whenNanos
    ) {
        spyInstance.atAfterInvoke(listenerId, clazz, invokeInfo, target, whenNanos);
    }

    public static void atInvokeException(
        String listenerId,
        Class<?> clazz,
        String invokeInfo,
        Object target,
        Throwable throwable,
        long whenNanos
    ) {
        spyInstance.atInvokeException(listenerId, clazz, invokeInfo, target, throwable, whenNanos);
    }

    /**
     * Special event used by vmtool instance tracking (constructor-return).
     */
    public static void onConstructed(String listenerId, Object instance) {
        spyInstance.onConstructed(listenerId, instance);
    }

    public abstract static class AbstractSpy {
        public abstract void atEnter(
            String listenerId,
            Class<?> clazz,
            String methodInfo,
            Object target,
            Object[] args,
            long startNanos
        );

        public abstract void atExit(
            String listenerId,
            Class<?> clazz,
            String methodInfo,
            Object target,
            Object[] args,
            Object returnObject,
            boolean returnCaptured,
            long startNanos,
            long durationNanos
        );

        public abstract void atExceptionExit(
            String listenerId,
            Class<?> clazz,
            String methodInfo,
            Object target,
            Object[] args,
            Throwable throwable,
            long startNanos,
            long durationNanos
        );

        public abstract void atBeforeInvoke(
            String listenerId,
            Class<?> clazz,
            String invokeInfo,
            Object target,
            long whenNanos
        );

        public abstract void atAfterInvoke(
            String listenerId,
            Class<?> clazz,
            String invokeInfo,
            Object target,
            long whenNanos
        );

        public abstract void atInvokeException(
            String listenerId,
            Class<?> clazz,
            String invokeInfo,
            Object target,
            Throwable throwable,
            long whenNanos
        );

        public abstract void onConstructed(String listenerId, Object instance);
    }

    private static final class NopSpy extends AbstractSpy {
        @Override
        public void atEnter(String listenerId, Class<?> clazz, String methodInfo, Object target, Object[] args, long startNanos) {
            // nop
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
            // nop
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
            // nop
        }

        @Override
        public void atBeforeInvoke(String listenerId, Class<?> clazz, String invokeInfo, Object target, long whenNanos) {
            // nop
        }

        @Override
        public void atAfterInvoke(String listenerId, Class<?> clazz, String invokeInfo, Object target, long whenNanos) {
            // nop
        }

        @Override
        public void atInvokeException(
            String listenerId,
            Class<?> clazz,
            String invokeInfo,
            Object target,
            Throwable throwable,
            long whenNanos
        ) {
            // nop
        }

        @Override
        public void onConstructed(String listenerId, Object instance) {
            // nop
        }
    }

    /**
     * Backward-compatible spy implementation that delegates to existing bootstrap interceptors.
     *
     * <p>This keeps behavior stable while allowing bytecode injections to converge to this single API class.</p>
     */
    private static final class LegacyBootstrapSpy extends AbstractSpy {
        @Override
        public void atEnter(String listenerId, Class<?> clazz, String methodInfo, Object target, Object[] args, long startNanos) {
            String className = safeClassName(clazz);
            MethodParts mp = MethodParts.parse(methodInfo);

            // watch: entry events
            WatchInterceptor.onMethodEntry(listenerId, className, mp.methodName, mp.methodDesc, args, startNanos, args != null);
            // trace: entry events
            TraceInterceptor.onMethodEntry(listenerId, className, mp.methodName, mp.methodDesc, startNanos);
            // stack: entry events (maxDepth resolved from registration; no need to bake into bytecode)
            StackInterceptor.onMethodEnter(listenerId, className, mp.methodName, mp.methodDesc);
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
            String className = safeClassName(clazz);
            MethodParts mp = MethodParts.parse(methodInfo);

            WatchInterceptor.onMethodExit(listenerId, className, mp.methodName, mp.methodDesc, returnObject, startNanos, durationNanos, returnCaptured);
            TraceInterceptor.onMethodExit(listenerId, className, mp.methodName, mp.methodDesc, startNanos, durationNanos, false);
            MonitorInterceptor.onMethodExit(listenerId, className, mp.methodName, mp.methodDesc, durationNanos, false);
            TtInterceptor.onMethodExit(listenerId, className, mp.methodName, mp.methodDesc, args, returnObject, startNanos, durationNanos);
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
            String className = safeClassName(clazz);
            MethodParts mp = MethodParts.parse(methodInfo);

            WatchInterceptor.onMethodException(listenerId, className, mp.methodName, mp.methodDesc, throwable, startNanos, durationNanos);
            TraceInterceptor.onMethodExit(listenerId, className, mp.methodName, mp.methodDesc, startNanos, durationNanos, true);
            MonitorInterceptor.onMethodExit(listenerId, className, mp.methodName, mp.methodDesc, durationNanos, true);
            TtInterceptor.onMethodException(listenerId, className, mp.methodName, mp.methodDesc, args, throwable, startNanos, durationNanos);
        }

        @Override
        public void atBeforeInvoke(String listenerId, Class<?> clazz, String invokeInfo, Object target, long whenNanos) {
            InvokeParts ip = InvokeParts.parse(invokeInfo);
            String ownerBinary = ip.ownerInternal.replace('/', '.');
            TraceInterceptor.onSubMethodCall(listenerId, ownerBinary, ip.methodName, ip.methodDesc, whenNanos);
        }

        @Override
        public void atAfterInvoke(String listenerId, Class<?> clazz, String invokeInfo, Object target, long whenNanos) {
            // Legacy trace currently only emits SUB_METHOD_CALL at "before invoke".
        }

        @Override
        public void atInvokeException(String listenerId, Class<?> clazz, String invokeInfo, Object target, Throwable throwable, long whenNanos) {
            // Legacy trace currently only emits SUB_METHOD_CALL at "before invoke".
        }

        @Override
        public void onConstructed(String listenerId, Object instance) {
            VmToolInterceptor.onConstructed(listenerId, instance);
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
            String name = methodInfo.substring(0, sep);
            String desc = methodInfo.substring(sep + 1);
            return new MethodParts(name, desc);
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

