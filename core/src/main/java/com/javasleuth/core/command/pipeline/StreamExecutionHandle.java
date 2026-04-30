package com.javasleuth.core.command.pipeline;

import com.javasleuth.core.command.CancellationTokenSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class StreamExecutionHandle {
    private final Future<?> future;
    private final CancellationTokenSource cancellation;
    private final AtomicBoolean started;
    private final CountDownLatch completionLatch;
    private final AtomicReference<StreamCompletion> completion;

    StreamExecutionHandle(
        Future<?> future,
        CancellationTokenSource cancellation,
        AtomicBoolean started,
        CountDownLatch completionLatch,
        AtomicReference<StreamCompletion> completion
    ) {
        this.future = future;
        this.cancellation = cancellation;
        this.started = started;
        this.completionLatch = completionLatch;
        this.completion = completion;
    }

    public Future<?> future() {
        return future;
    }

    public CancellationTokenSource cancellation() {
        return cancellation;
    }

    public boolean isStarted() {
        return started != null && started.get();
    }

    public boolean isDone() {
        return completion != null && completion.get() != null;
    }

    public StreamCompletion awaitCompletion() throws InterruptedException {
        completionLatch.await();
        StreamCompletion result = completion();
        return result != null ? result : StreamCompletion.failed(null);
    }

    public void cancel(String reason) {
        if (cancellation != null) {
            cancellation.cancel();
        }
        if (future != null) {
            future.cancel(true);
        }
        if (!isStarted()) {
            complete(StreamCompletion.cancelled(reason != null ? reason : "cancelled"));
        }
    }

    public StreamCompletion completion() {
        return completion != null ? completion.get() : null;
    }

    boolean complete(StreamCompletion result) {
        if (completion == null || completionLatch == null) {
            return false;
        }
        if (completion.compareAndSet(null, result)) {
            completionLatch.countDown();
            return true;
        }
        return false;
    }
}
