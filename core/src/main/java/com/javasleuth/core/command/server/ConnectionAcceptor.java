package com.javasleuth.core.command.server;

/**
 * 连接 accept 循环与过载拒绝策略。
 *
 * <p>将 ServerSocket accept 循环从 CommandProcessor 中剥离，避免生命周期/协议/输出混杂。</p>
 */
import com.javasleuth.foundation.command.protocol.Utf8LineCodec;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.util.SleuthLogger;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConnectionAcceptor {

    public void acceptLoop(
        AtomicBoolean running,
        ServerSocket serverSocket,
        ThreadPoolExecutor clientExecutor,
        CommandClientHandler clientHandler,
        ProductionConfig config,
        AuditLogger auditLogger,
        MetricsCollector metricsCollector
    ) {
        if (serverSocket == null) {
            return;
        }

        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(SleuthConfigSchema.SERVER_CONNECTION_TIMEOUT_MS.read(config));

                if (shouldRejectByMaxConnections(clientSocket, config, auditLogger, metricsCollector)) {
                    continue;
                }
                if (isClientExecutorSaturated(clientExecutor)) {
                    rejectBusy(clientSocket, auditLogger, metricsCollector, "executor queue full");
                    continue;
                }

                try {
                    clientExecutor.execute(() -> clientHandler.handle(clientSocket));
                    metricsCollector.recordClientConnection();
                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                    rejectBusy(clientSocket, auditLogger, metricsCollector, "executor rejected");
                }

            } catch (java.net.SocketTimeoutException e) {
                // Normal timeout, continue.
            } catch (java.net.SocketException e) {
                // Socket closed during shutdown - normal.
                if (running.get()) {
                    SleuthLogger.warn("Socket error: " + e.getMessage(), e);
                }
                break;
            } catch (IOException e) {
                if (running.get()) {
                    SleuthLogger.warn("Error accepting client connection: " + e.getMessage(), e);
                    metricsCollector.recordError("connection_error");
                    try {
                        TimeUnit.MILLISECONDS.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private boolean shouldRejectByMaxConnections(
        Socket clientSocket,
        ProductionConfig config,
        AuditLogger auditLogger,
        MetricsCollector metricsCollector
    ) {
        int maxConnections = SleuthConfigSchema.SERVER_MAX_CONNECTIONS.read(config);
        int active = metricsCollector.getActiveConnections();
        if (maxConnections <= 0) {
            return false;
        }
        if (active < maxConnections) {
            return false;
        }

        String remote = String.valueOf(clientSocket.getRemoteSocketAddress());
        auditLogger.logSecurityViolation(null, remote, "MAX_CONNECTIONS", "Rejected connection: active=" + active + ", max=" + maxConnections);
        try {
            BufferedOutputStream out = new BufferedOutputStream(clientSocket.getOutputStream());
            Utf8LineCodec.writeLine(out, "ERROR: too many connections (max=" + maxConnections + ")", true);
        } catch (Exception ignore) {
            // ignore
        } finally {
            try {
                clientSocket.close();
            } catch (Exception ignore) {
                // ignore
            }
        }
        return true;
    }

    private void rejectBusy(Socket clientSocket, AuditLogger auditLogger, MetricsCollector metricsCollector, String reason) {
        String remote = String.valueOf(clientSocket.getRemoteSocketAddress());
        auditLogger.logSystemEvent("SERVER_OVERLOADED", "Rejected connection due to " + reason + ": remote=" + remote);
        metricsCollector.recordError("server_overload");
        try {
            BufferedOutputStream out = new BufferedOutputStream(clientSocket.getOutputStream());
            Utf8LineCodec.writeLine(out, "ERROR: server busy (" + reason + ")", true);
        } catch (Exception ignore) {
            // ignore
        } finally {
            try {
                clientSocket.close();
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    private static boolean isClientExecutorSaturated(ThreadPoolExecutor clientExecutor) {
        try {
            if (clientExecutor == null) {
                return false;
            }
            int active = clientExecutor.getActiveCount();
            int max = clientExecutor.getMaximumPoolSize();
            if (max <= 0) {
                return false;
            }
            int remaining = clientExecutor.getQueue() != null ? clientExecutor.getQueue().remainingCapacity() : Integer.MAX_VALUE;
            return active >= max && remaining <= 0;
        } catch (Exception e) {
            return false;
        }
    }
}
