package com.javasleuth.core.command.server;

/**
 * 关闭编排（graceful/emergency）。
 *
 * <p>将 shutdown 步骤编排从 CommandProcessor 中剥离，确保幂等且可测试。</p>
 */
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.util.SleuthLogger;
import com.javasleuth.core.command.CommandPipeline;
import com.javasleuth.core.command.CommandRegistry;
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
    private final AuthorizationManager authorizationManager;

    public ShutdownCoordinator(
        AtomicBoolean running,
        ThreadPoolExecutor clientExecutor,
        MetricsCollector metricsCollector,
        AuditLogger auditLogger,
        CommandRegistry registry,
        CommandPipeline pipeline,
        AuthorizationManager authorizationManager
    ) {
        this.running = running;
        this.clientExecutor = clientExecutor;
        this.metricsCollector = metricsCollector;
        this.auditLogger = auditLogger;
        this.registry = registry;
        this.pipeline = pipeline;
        this.authorizationManager = authorizationManager;
    }

    public boolean shutdownGracefully(ServerSocket serverSocket, int timeoutSeconds) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return false;
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
                if (authorizationManager != null) {
                    authorizationManager.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }

            metricsCollector.recordServerShutdown();
            metricsCollector.shutdown();

            long shutdownDuration = System.currentTimeMillis() - shutdownStart;
            auditLogger.logSystemEvent("SHUTDOWN_COMPLETE", "Graceful shutdown completed in " + shutdownDuration + "ms");

            SleuthLogger.info("🎉 Command processor shutdown complete in " + shutdownDuration + "ms");
        } finally {
            shuttingDown.set(false);
        }
        return true;
    }

    public boolean emergencyShutdown(ServerSocket serverSocket) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return false;
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
                if (authorizationManager != null) {
                    authorizationManager.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                metricsCollector.shutdown();
            } catch (Exception ignore) {
                // ignore
            }

            SleuthLogger.error("🚨 Emergency shutdown complete");
        } finally {
            shuttingDown.set(false);
        }
        return true;
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
