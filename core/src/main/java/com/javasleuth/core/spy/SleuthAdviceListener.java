package com.javasleuth.core.spy;

/**
 * Core-side listener API for unified advice events.
 *
 * <p>This is intentionally allocation-minimal: it mirrors {@code SleuthSpyAPI} callbacks and avoids
 * creating per-event objects on hot paths.</p>
 *
 * <p>Listeners must be best-effort: any exception must be contained and must not affect the target application.</p>
 */
public interface SleuthAdviceListener {

    default void onEnter(
        Class<?> clazz,
        String methodInfo,
        Object target,
        Object[] args,
        long startNanos
    ) {}

    default void onExit(
        Class<?> clazz,
        String methodInfo,
        Object target,
        Object[] args,
        Object returnObject,
        boolean returnCaptured,
        long startNanos,
        long durationNanos
    ) {}

    default void onExceptionExit(
        Class<?> clazz,
        String methodInfo,
        Object target,
        Object[] args,
        Throwable throwable,
        long startNanos,
        long durationNanos
    ) {}

    default void onBeforeInvoke(
        Class<?> clazz,
        String invokeInfo,
        Object target,
        long whenNanos
    ) {}

    default void onAfterInvoke(
        Class<?> clazz,
        String invokeInfo,
        Object target,
        long whenNanos
    ) {}

    default void onInvokeException(
        Class<?> clazz,
        String invokeInfo,
        Object target,
        Throwable throwable,
        long whenNanos
    ) {}

    default void onConstructed(Object instance) {}
}

