package com.javasleuth.core.command;

import java.util.ArrayList;

/**
 * Builds closeables for command-processor-owned resources.
 */
final class ResourceCloser {
    private ResourceCloser() {}

    static AutoCloseable forOwnedResources(final RuntimeServices services) {
        if (services == null) {
            return null;
        }
        return fromOwnedResources(
            services.ownership,
            services.auditLogger,
            services.authenticationManager,
            services.dangerousConfirm,
            services.performanceOptimizer,
            new AutoCloseable() {
                @Override
                public void close() {
                    services.vmToolSessionRegistry.shutdown(services.instrumentation, services.transformer, "shutdown");
                }
            },
            new AutoCloseable() {
                @Override
                public void close() {
                    services.clientSessionRegistry.shutdown("shutdown");
                }
            },
            new AutoCloseable() {
                @Override
                public void close() {
                    services.jobManager.shutdown("shutdown");
                }
            },
            new AutoCloseable() {
                @Override
                public void close() {
                    services.enhancementSessionRegistry.closeAll("shutdown");
                }
            }
        );
    }

    static AutoCloseable fromOwnedResources(
        ResourceOwnership ownership,
        AutoCloseable auditLogger,
        AutoCloseable authenticationManager,
        AutoCloseable dangerousConfirm,
        AutoCloseable performanceOptimizer,
        AutoCloseable vmToolSessionRegistry,
        AutoCloseable clientSessionRegistry,
        AutoCloseable jobManager,
        AutoCloseable enhancementSessionRegistry
    ) {
        if (ownership == null || !ownership.hasOwnedResources()) {
            return null;
        }

        ArrayList<AutoCloseable> closeables = new ArrayList<AutoCloseable>();

        // CommandProcessorOwnedResources closes in reverse order. Add dependencies first.
        if (ownership.ownsAuditLogger()) {
            closeables.add(auditLogger);
        }
        if (ownership.ownsAuthenticationManager()) {
            closeables.add(authenticationManager);
        }
        if (ownership.ownsDangerousConfirm()) {
            closeables.add(dangerousConfirm);
        }
        if (ownership.ownsPerformanceOptimizer()) {
            closeables.add(performanceOptimizer);
        }
        if (ownership.ownsVmToolSessionRegistry()) {
            closeables.add(vmToolSessionRegistry);
        }
        if (ownership.ownsClientSessionRegistry()) {
            closeables.add(clientSessionRegistry);
        }
        if (ownership.ownsJobManager()) {
            closeables.add(jobManager);
        }
        if (ownership.ownsEnhancementSessionRegistry()) {
            closeables.add(enhancementSessionRegistry);
        }

        return new CommandProcessorOwnedResources(closeables.toArray(new AutoCloseable[0]));
    }
}
