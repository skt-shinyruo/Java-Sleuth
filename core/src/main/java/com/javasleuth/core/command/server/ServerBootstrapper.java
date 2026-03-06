package com.javasleuth.core.command.server;

/**
 * 命令服务启动自举（bootstrap）。
 *
 * <p>负责日志/JobManager 配置与安全启动边界校验（loopback-only bind）。</p>
 */
import com.javasleuth.core.command.JobManager;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.JobsConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.util.SleuthLogger;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerBootstrapper {
    public void configureLoggingProvider(ProductionConfig config) {
        if (config == null) {
            return;
        }
        try {
            final ProductionConfig cfg = config;
            SleuthLogger.setConfigProvider(new SleuthLogger.ConfigProvider() {
                @Override
                public String getString(String key, String defaultValue) {
                    return cfg.getString(key, defaultValue);
                }

                @Override
                public boolean getBoolean(String key, boolean defaultValue) {
                    return cfg.getBoolean(key, defaultValue);
                }

                @Override
                public boolean isLoading() {
                    return false;
                }
            });
        } catch (Exception ignore) {
            // 忽略
        }
    }

    public void configureJobManager(JobManager jobManager, ProductionConfig config) {
        if (jobManager == null || config == null) {
            return;
        }
        try {
            SleuthConfig typed = SleuthConfigParser.parse(config.snapshot());
            JobsConfig jobs = typed.jobs();
            jobManager.configureRetention(
                jobs.getMaxJobs(),
                jobs.getTtlMs(),
                jobs.getOutputMaxBytes()
            );
            jobManager.configureExecution(
                jobs.getMaxRunning(),
                jobs.getQueueCapacity()
            );
        } catch (Exception e) {
            SleuthLogger.debug("JobManager config skipped (best-effort): " + e.getMessage(), e);
        }
    }

    public ServerSocket bindAndValidate(
        AtomicBoolean running,
        ProductionConfig config,
        AuditLogger auditLogger,
        MetricsCollector metricsCollector
    ) throws IOException {
        SleuthConfig typed = SleuthConfigParser.parse(config.snapshot());
        int port = typed.server().getPort();
        String bindAddress = typed.server().getBindAddress();

        if (!isLoopbackBind(bindAddress)) {
            SleuthLogger.error("❌ SECURITY ERROR: Refusing to start with non-loopback bind address: " + bindAddress);
            SleuthLogger.error("Fix: bind to 127.0.0.1 / localhost / ::1 (loopback-only)");
            auditLogger.logSystemEvent(
                "SERVER_START_BLOCKED",
                "Refused to start: non-loopback bind=" + bindAddress
            );
            if (running != null) {
                running.set(false);
            }
            return null;
        }

        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(bindAddress, port));
        serverSocket.setSoTimeout(typed.server().getSocketTimeoutMs());

        SleuthLogger.info("🚀 Java-Sleuth listening on " + bindAddress + ":" + port);
        metricsCollector.recordServerStartup();
        auditLogger.logSystemEvent("SERVER_START", "Server started on port " + port);
        return serverSocket;
    }

    private static boolean isLoopbackBind(String bindAddress) {
        if (bindAddress == null) {
            return true;
        }
        String v = bindAddress.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return true;
        }
        return "127.0.0.1".equals(v) || "localhost".equals(v) || "::1".equals(v);
    }
}
