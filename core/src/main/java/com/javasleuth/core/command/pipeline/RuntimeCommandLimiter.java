package com.javasleuth.core.command.pipeline;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime-scoped command limiter owned by a single {@link CommandExecutionEngine}.
 */
final class RuntimeCommandLimiter {
    private final ProductionConfig config;
    private final Object highImpactLock = new Object();
    private int highImpactRunning;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    RuntimeCommandLimiter(ProductionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
    }

    Permit acquire(CommandMeta meta) throws Exception {
        if (meta == null || meta.getImpactLevel() != CommandMeta.ImpactLevel.HIGH) {
            return Permit.none();
        }
        if (closed.get()) {
            throw new Exception("Command execution engine is shutting down");
        }

        int limit = SleuthConfigSchema.SECURITY_IMPACT_HIGH_CONCURRENT_LIMIT.read(config);
        if (limit <= 0) {
            return Permit.none();
        }

        synchronized (highImpactLock) {
            if (closed.get()) {
                throw new Exception("Command execution engine is shutting down");
            }
            if (highImpactRunning >= limit) {
                throw new Exception("High impact command is already running; please retry later");
            }
            highImpactRunning++;
            return new Permit(this);
        }
    }

    void close() {
        closed.set(true);
    }

    private void releaseHighImpact() {
        synchronized (highImpactLock) {
            if (highImpactRunning > 0) {
                highImpactRunning--;
            }
        }
    }

    static final class Permit {
        private final RuntimeCommandLimiter owner;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Permit(RuntimeCommandLimiter owner) {
            this.owner = owner;
        }

        static Permit none() {
            return new Permit(null);
        }

        void release() {
            if (owner == null) {
                return;
            }
            if (released.compareAndSet(false, true)) {
                owner.releaseHighImpact();
            }
        }
    }
}
