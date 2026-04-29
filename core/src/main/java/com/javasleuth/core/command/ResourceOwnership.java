package com.javasleuth.core.command;

/**
 * Ownership flags for resources created by command-processor default wiring.
 */
final class ResourceOwnership {
    private final boolean ownsAuditLogger;
    private final boolean ownsAuthenticationManager;
    private final boolean ownsDangerousConfirm;
    private final boolean ownsPerformanceOptimizer;
    private final boolean ownsVmToolSessionRegistry;
    private final boolean ownsClientSessionRegistry;
    private final boolean ownsJobManager;
    private final boolean ownsEnhancementSessionRegistry;

    ResourceOwnership(
        boolean ownsAuditLogger,
        boolean ownsAuthenticationManager,
        boolean ownsDangerousConfirm,
        boolean ownsPerformanceOptimizer,
        boolean ownsVmToolSessionRegistry,
        boolean ownsClientSessionRegistry,
        boolean ownsJobManager,
        boolean ownsEnhancementSessionRegistry
    ) {
        this.ownsAuditLogger = ownsAuditLogger;
        this.ownsAuthenticationManager = ownsAuthenticationManager;
        this.ownsDangerousConfirm = ownsDangerousConfirm;
        this.ownsPerformanceOptimizer = ownsPerformanceOptimizer;
        this.ownsVmToolSessionRegistry = ownsVmToolSessionRegistry;
        this.ownsClientSessionRegistry = ownsClientSessionRegistry;
        this.ownsJobManager = ownsJobManager;
        this.ownsEnhancementSessionRegistry = ownsEnhancementSessionRegistry;
    }

    boolean ownsAuditLogger() {
        return ownsAuditLogger;
    }

    boolean ownsAuthenticationManager() {
        return ownsAuthenticationManager;
    }

    boolean ownsDangerousConfirm() {
        return ownsDangerousConfirm;
    }

    boolean ownsPerformanceOptimizer() {
        return ownsPerformanceOptimizer;
    }

    boolean ownsVmToolSessionRegistry() {
        return ownsVmToolSessionRegistry;
    }

    boolean ownsClientSessionRegistry() {
        return ownsClientSessionRegistry;
    }

    boolean ownsJobManager() {
        return ownsJobManager;
    }

    boolean ownsEnhancementSessionRegistry() {
        return ownsEnhancementSessionRegistry;
    }

    boolean hasOwnedResources() {
        return ownsAuditLogger
            || ownsAuthenticationManager
            || ownsDangerousConfirm
            || ownsPerformanceOptimizer
            || ownsVmToolSessionRegistry
            || ownsClientSessionRegistry
            || ownsJobManager
            || ownsEnhancementSessionRegistry;
    }
}
