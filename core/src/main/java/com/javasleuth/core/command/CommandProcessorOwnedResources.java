package com.javasleuth.core.command;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A composite closeable for resources created/owned by {@link CommandProcessorFactory}.
 *
 * <p>Background threads (audit/session cleanup) and attach-scope registries are created during
 * default wiring and must be released when the processor shuts down. When dependencies are
 * injected (runtime container), {@code ownedResources} is expected to be {@code null} or empty
 * to avoid double-closing services managed by the caller.
 */
final class CommandProcessorOwnedResources implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AutoCloseable[] closeables;

    CommandProcessorOwnedResources(AutoCloseable... closeables) {
        if (closeables == null || closeables.length == 0) {
            this.closeables = new AutoCloseable[0];
            return;
        }
        int n = 0;
        for (AutoCloseable c : closeables) {
            if (c != null) {
                n++;
            }
        }
        if (n == closeables.length) {
            this.closeables = closeables;
            return;
        }
        AutoCloseable[] filtered = new AutoCloseable[n];
        int i = 0;
        for (AutoCloseable c : closeables) {
            if (c != null) {
                filtered[i++] = c;
            }
        }
        this.closeables = filtered;
    }

    public boolean isEmpty() {
        return closeables.length == 0;
    }

    @Override
    public void close() {
        if (closeables.length == 0) {
            return;
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        // Close in reverse order to respect dependency ordering (similar to try-with-resources).
        for (int i = closeables.length - 1; i >= 0; i--) {
            AutoCloseable c = closeables[i];
            if (c == null) {
                continue;
            }
            try {
                c.close();
            } catch (Exception ignore) {
                // best-effort
            }
        }
    }
}

