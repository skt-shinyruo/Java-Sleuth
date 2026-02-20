package com.javasleuth.command.server;

/**
 * 关闭编排（graceful/emergency）。
 *
 * <p>将 shutdown 步骤编排从 CommandProcessor 中剥离，确保幂等且可测试。</p>
 */
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.security.AuditLogger;
import com.javasleuth.security.AuthenticationManager;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.DangerousCommandConfirmationManager;
import com.javasleuth.security.RequestSecurityManager;
import com.javasleuth.util.MemoryOptimizer;
import com.javasleuth.util.PerformanceOptimizer;
import com.javasleuth.util.SleuthLogger;
import com.javasleuth.command.CommandPipeline;
import com.javasleuth.command.CommandRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ShutdownCoordinator {
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean running;
    private final ThreadPoolExecutor clientExecutor;
    private final MetricsCollector metricsCollector;
    private final AuditLogger auditLogger;
    private final CommandRegistry registry;
    private final CommandPipeline pipeline;
    private final AuthenticationManager authenticationManager;
    private final AuthorizationManager authorizationManager;
    private final RequestSecurityManager requestSecurityManager;
    private final DangerousCommandConfirmationManager dangerousConfirm;

    public ShutdownCoordinator(
        AtomicBoolean running,
        ThreadPoolExecutor clientExecutor,
        MetricsCollector metricsCollector,
        AuditLogger auditLogger,
        CommandRegistry registry,
        CommandPipeline pipeline,
        AuthenticationManager authenticationManager,
        AuthorizationManager authorizationManager,
        RequestSecurityManager requestSecurityManager,
        DangerousCommandConfirmationManager dangerousConfirm
    ) {
        this.running = running;
        this.clientExecutor = clientExecutor;
        this.metricsCollector = metricsCollector;
        this.auditLogger = auditLogger;
        this.registry = registry;
        this.pipeline = pipeline;
        this.authenticationManager = authenticationManager;
        this.authorizationManager = authorizationManager;
        this.requestSecurityManager = requestSecurityManager;
        this.dangerousConfirm = dangerousConfirm;
    }

    public void shutdownGracefully(ServerSocket serverSocket, int timeoutSeconds) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        try {
            SleuthLogger.info("🔄 Initiating graceful shutdown (timeout: " + timeoutSeconds + "s)...");
            long shutdownStart = System.currentTimeMillis();
            auditLogger.logSystemEvent("SHUTDOWN_INITIATED", "Graceful shutdown started with " + timeoutSeconds + "s timeout");

            running.set(false);

            closeServerSocket(serverSocket);

            waitActiveConnections(timeoutSeconds);

            shutdownExecutor(timeoutSeconds);

            try {
                if (pipeline != null) {
                    pipeline.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }

            // Release plugin classloader resources (important on Windows to avoid JAR locks).
            try {
                registry.shutdown();
            } catch (Exception ignore) {
                // ignore
            }

            try {
                if (authenticationManager != null) {
                    authenticationManager.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (authorizationManager != null) {
                    authorizationManager.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (requestSecurityManager != null) {
                    requestSecurityManager.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (dangerousConfirm != null) {
                    dangerousConfirm.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }

            // Clear global singletons too (best-effort), to support detach → re-attach in the same JVM.
            try {
                AuthenticationManager.shutdownInstance();
            } catch (Exception ignore) {
                // ignore
            }
            try {
                DangerousCommandConfirmationManager.shutdownInstance();
            } catch (Exception ignore) {
                // ignore
            }

            PerformanceOptimizer.shutdown();
            try {
                MemoryOptimizer.shutdownInstance();
            } catch (Exception ignore) {
                // ignore
            }

            metricsCollector.recordServerShutdown();
            metricsCollector.shutdown();

            long shutdownDuration = System.currentTimeMillis() - shutdownStart;
            auditLogger.logSystemEvent("SHUTDOWN_COMPLETE", "Graceful shutdown completed in " + shutdownDuration + "ms");
            auditLogger.shutdown();

            SleuthLogger.info("🎉 Command processor shutdown complete in " + shutdownDuration + "ms");
        } finally {
            shuttingDown.set(false);
        }
    }

    public void emergencyShutdown(ServerSocket serverSocket) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        try {
            SleuthLogger.error("🚨 EMERGENCY SHUTDOWN INITIATED");
            auditLogger.logSystemEvent("EMERGENCY_SHUTDOWN", "Emergency shutdown initiated");
            running.set(false);

            closeServerSocket(serverSocket);

            try {
                clientExecutor.shutdownNow();
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (pipeline != null) {
                    pipeline.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                registry.shutdown();
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (authenticationManager != null) {
                    authenticationManager.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (authorizationManager != null) {
                    authorizationManager.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (requestSecurityManager != null) {
                    requestSecurityManager.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (dangerousConfirm != null) {
                    dangerousConfirm.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                AuthenticationManager.shutdownInstance();
            } catch (Exception ignore) {
                // ignore
            }
            try {
                DangerousCommandConfirmationManager.shutdownInstance();
            } catch (Exception ignore) {
                // ignore
            }
            try {
                PerformanceOptimizer.shutdown();
            } catch (Exception ignore) {
                // ignore
            }
            try {
                MemoryOptimizer.shutdownInstance();
            } catch (Exception ignore) {
                // ignore
            }
            try {
                metricsCollector.shutdown();
            } catch (Exception ignore) {
                // ignore
            }
            try {
                auditLogger.shutdown();
            } catch (Exception ignore) {
                // ignore
            }

            SleuthLogger.error("🚨 Emergency shutdown complete");
        } finally {
            shuttingDown.set(false);
        }
    }

    private void closeServerSocket(ServerSocket serverSocket) {
        if (serverSocket == null) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
            SleuthLogger.warn("⚠️ Error closing server socket: " + e.getMessage(), e);
        }
    }

    private void waitActiveConnections(int timeoutSeconds) {
        int activeConnections = metricsCollector.getActiveConnections();
        if (activeConnections <= 0) {
            return;
        }
        long waitStart = System.currentTimeMillis();
        long maxWait = Math.max(1, timeoutSeconds) * 1000L / 2;
        while (metricsCollector.getActiveConnections() > 0 && (System.currentTimeMillis() - waitStart) < maxWait) {
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void shutdownExecutor(int timeoutSeconds) {
        try {
            clientExecutor.shutdown();
        } catch (Exception ignore) {
            return;
        }
        try {
            int waitSeconds = Math.max(1, timeoutSeconds / 3);
            if (!clientExecutor.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
                SleuthLogger.warn("⚠️ Client executor did not terminate gracefully, forcing shutdown...");
                clientExecutor.shutdownNow();
                clientExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            try {
                clientExecutor.shutdownNow();
            } catch (Exception ignore) {
                // ignore
            }
            Thread.currentThread().interrupt();
        }
    }
}
