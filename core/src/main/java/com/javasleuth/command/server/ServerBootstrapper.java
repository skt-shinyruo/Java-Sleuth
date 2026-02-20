package com.javasleuth.command.server;

/**
 * 命令服务启动自举（bootstrap）。
 *
 * <p>负责日志/JobManager 配置与安全启动边界校验（HMAC secret 自举、非回环 bind 的安全模式约束）。</p>
 */
import com.javasleuth.command.JobManager;
import com.javasleuth.config.ConfigUpdateSource;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.security.AuditLogger;
import com.javasleuth.util.SleuthLogger;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerBootstrapper {
    private static final SecureRandom SECRET_RANDOM = new SecureRandom();

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
                    return ProductionConfig.isLoading();
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
            jobManager.configureRetention(
                config.getJobsMax(),
                config.getJobsTtlMs(),
                config.getJobsOutputMaxBytes()
            );
            jobManager.configureExecution(
                config.getJobsMaxRunning(),
                config.getJobsQueueCapacity()
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
        int port = config.getServerPort();
        String bindAddress = config.getServerBindAddress();

        if (!isLoopbackBind(bindAddress) && "off".equalsIgnoreCase(config.getSecurityMode())) {
            SleuthLogger.error("❌ SECURITY ERROR: Refusing to start with non-loopback bind and security.mode=off");
            SleuthLogger.error("Fix: set security.mode=hmac + security.hmac.secret, or bind to 127.0.0.1/::1");
            auditLogger.logSystemEvent(
                "SERVER_START_BLOCKED",
                "Refused to start: security.mode=off with non-loopback bind=" + bindAddress
            );
            if (running != null) {
                running.set(false);
            }
            return null;
        }

        if ("hmac".equalsIgnoreCase(config.getSecurityMode())) {
            if (!ensureHmacSecret(config, auditLogger, bindAddress)) {
                if (running != null) {
                    running.set(false);
                }
                return null;
            }
        }

        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(bindAddress, port));
        serverSocket.setSoTimeout(config.getSocketTimeout());

        SleuthLogger.info("🚀 Java-Sleuth listening on " + bindAddress + ":" + port);
        metricsCollector.recordServerStartup();
        auditLogger.logSystemEvent("SERVER_START", "Server started on port " + port);
        return serverSocket;
    }

    private boolean ensureHmacSecret(ProductionConfig config, AuditLogger auditLogger, String bindAddress) {
        String secret = config.getSecurityHmacSecret();
        if (secret != null && !secret.trim().isEmpty()) {
            return true;
        }

        if (isLoopbackBind(bindAddress) && config.isHmacSecretAutogenOnLoopbackEnabled()) {
            int bytes = config.getHmacBootstrapSecretBytes();
            String generated = generateHmacSecret(bytes);
            config.setRuntimeConfig("security.hmac.secret", generated, ConfigUpdateSource.INTERNAL);
            auditLogger.logSystemEvent("HMAC_SECRET_AUTOGEN", "Generated temporary HMAC secret for loopback bind=" + bindAddress);
            if (config.isHmacSecretAutogenPrintEnabled()) {
                SleuthLogger.warn("⚠️ SECURITY NOTICE: security.mode=hmac but security.hmac.secret is empty.");
                SleuthLogger.warn("Generated a temporary HMAC secret for loopback-only listener.");
                SleuthLogger.warn("To persist, set security.hmac.secret in your config file.");
                if (System.console() != null) {
                    SleuthLogger.warn("Temporary security.hmac.secret = " + generated);
                } else {
                    SleuthLogger.warn("Temporary security.hmac.secret was generated but NOT printed (no interactive console).");
                    SleuthLogger.warn("Fix: set security.hmac.secret explicitly in config, or run in interactive console to print it.");
                }
            }
            return true;
        }

        SleuthLogger.error("❌ SECURITY ERROR: Refusing to start with security.mode=hmac but empty security.hmac.secret");
        SleuthLogger.error("Fix: set security.hmac.secret, or bind to 127.0.0.1/::1 to enable autogen");
        auditLogger.logSystemEvent(
            "SERVER_START_BLOCKED",
            "Refused to start: security.mode=hmac but security.hmac.secret is empty"
        );
        return false;
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

    private static String generateHmacSecret(int bytes) {
        int size = bytes <= 0 ? 32 : Math.min(bytes, 128);
        byte[] buf = new byte[size];
        SECRET_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
