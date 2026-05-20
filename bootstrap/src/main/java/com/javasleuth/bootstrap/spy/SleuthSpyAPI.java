package com.javasleuth.bootstrap.spy;

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
 * <p>The default implementation is NOP. Core installs a dispatcher when the attach runtime is ready; calls
 * before install or after detach must never publish through a second bootstrap-side channel.</p>
 */
public final class SleuthSpyAPI {
    public static final AbstractSpy NOP_SPY = new NopSpy();

    private static volatile AbstractSpy spyInstance = NOP_SPY;
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

}
