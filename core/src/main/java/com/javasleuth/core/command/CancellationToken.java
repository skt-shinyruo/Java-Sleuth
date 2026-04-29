package com.javasleuth.core.command;

public interface CancellationToken {
    CancellationToken NONE = new CancellationToken() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void throwIfCancelled() throws InterruptedException {
        }
    };

    boolean isCancelled();

    void throwIfCancelled() throws InterruptedException;
}
