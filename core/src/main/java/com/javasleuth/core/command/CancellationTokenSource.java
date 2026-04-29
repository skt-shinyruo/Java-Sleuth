package com.javasleuth.core.command;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationTokenSource {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final CancellationToken token = new CancellationToken() {
        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void throwIfCancelled() throws InterruptedException {
            if (cancelled.get()) {
                throw new InterruptedException("cancelled");
            }
        }
    };

    public CancellationToken token() {
        return token;
    }

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
