package com.javasleuth.foundation.config.model;

import com.javasleuth.foundation.config.ConfigOrigin;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.LogPathResolver;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import com.javasleuth.foundation.util.SleuthLogger;
import java.util.Locale;

/**
 * 强类型配置解析器：从 {@link ConfigView} 读取并产出 {@link SleuthConfig}。
 *
 * <p>设计目标：</p>
 * <ul>
 *   <li>集中管理 key/默认值/校验与归一化规则</li>
 *   <li>在连接/会话等边界处一次性解析，避免运行时散落的默认值漂移</li>
 * </ul>
 */
public final class SleuthConfigParser {
    private SleuthConfigParser() {
    }

    public static SleuthConfig parse(ConfigView config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }

        ServerConfig server = parseServer(config);
        ProtocolConfig protocol = parseProtocol(config);
        SecurityConfig security = parseSecurity(config);
        PerformanceConfig performance = parsePerformance(config);
        JobsConfig jobs = parseJobs(config);
        MonitoringConfig monitoring = parseMonitoring(config);
        LoggingConfig logging = parseLogging(config);
        PluginsConfig plugins = parsePlugins(config);

        return new SleuthConfig(server, protocol, security, performance, jobs, monitoring, logging, plugins);
    }

    private static ServerConfig parseServer(ConfigView config) {
        String bind = normalizeNonBlank(SleuthConfigSchema.SERVER_BIND_ADDRESS.read(config), "127.0.0.1");
        int port = SleuthConfigSchema.SERVER_PORT.read(config);
        int maxConn = SleuthConfigSchema.SERVER_MAX_CONNECTIONS.read(config);
        int queueCap = SleuthConfigSchema.SERVER_EXECUTOR_QUEUE_CAPACITY.read(config);
        int connTimeout = SleuthConfigSchema.SERVER_CONNECTION_TIMEOUT_MS.read(config);
        int sockTimeout = SleuthConfigSchema.SERVER_SOCKET_TIMEOUT_MS.read(config);
        return new ServerConfig(bind, port, maxConn, queueCap, connTimeout, sockTimeout);
    }

    private static ProtocolConfig parseProtocol(ConfigView config) {
        boolean streaming = SleuthConfigSchema.PROTOCOL_STREAMING_ENABLED.read(config);
        int frameMaxPayload = SleuthConfigSchema.PROTOCOL_FRAME_MAX_PAYLOAD.read(config);
        int textMaxLineBytes = SleuthConfigSchema.PROTOCOL_TEXT_MAX_LINE_BYTES.read(config);

        return new ProtocolConfig(streaming, frameMaxPayload, textMaxLineBytes);
    }

    private static SecurityConfig parseSecurity(ConfigView config) {
        boolean inputValidation = SleuthConfigSchema.SECURITY_INPUT_VALIDATION.read(config);
        boolean auditLogging = SleuthConfigSchema.SECURITY_AUDIT_LOGGING.read(config);
        boolean authorization = SleuthConfigSchema.SECURITY_AUTHORIZATION_ENABLED.read(config);
        boolean anonymousViewer = SleuthConfigSchema.SECURITY_ANONYMOUS_VIEWER.read(config);
        int maxCommandLength = SleuthConfigSchema.SECURITY_MAX_COMMAND_LENGTH.read(config);
        String allowedCommands = normalizeNonBlank(SleuthConfigSchema.SECURITY_ALLOWED_COMMANDS.read(config), "*");

        boolean dangerousConfirmEnabled = SleuthConfigSchema.SECURITY_DANGEROUS_CONFIRM_ENABLED.read(config);
        long dangerousConfirmTtlMs = SleuthConfigSchema.SECURITY_DANGEROUS_CONFIRM_TTL_MS.read(config);
        int dangerousConfirmTokenBytes = SleuthConfigSchema.SECURITY_DANGEROUS_CONFIRM_TOKEN_BYTES.read(config);
        int dangerousConfirmCacheSize = SleuthConfigSchema.SECURITY_DANGEROUS_CONFIRM_CACHE_SIZE.read(config);

        boolean impactHighConfirmEnabled = SleuthConfigSchema.SECURITY_IMPACT_HIGH_CONFIRM_ENABLED.read(config);
        int impactHighConcurrentLimit = SleuthConfigSchema.SECURITY_IMPACT_HIGH_CONCURRENT_LIMIT.read(config);

        boolean passwordAuthEnabled = SleuthConfigSchema.SECURITY_AUTH_PASSWORD_ENABLED.read(config);
        String adminPassword = SleuthConfigSchema.SECURITY_AUTH_ADMIN_PASSWORD.read(config);
        String operatorPassword = SleuthConfigSchema.SECURITY_AUTH_OPERATOR_PASSWORD.read(config);
        String viewerPassword = SleuthConfigSchema.SECURITY_AUTH_VIEWER_PASSWORD.read(config);

        return new SecurityConfig(
            inputValidation,
            auditLogging,
            authorization,
            anonymousViewer,
            maxCommandLength,
            allowedCommands,
            dangerousConfirmEnabled,
            dangerousConfirmTtlMs,
            dangerousConfirmTokenBytes,
            dangerousConfirmCacheSize,
            impactHighConfirmEnabled,
            impactHighConcurrentLimit,
            passwordAuthEnabled,
            adminPassword,
            operatorPassword,
            viewerPassword
        );
    }

    private static PerformanceConfig parsePerformance(ConfigView config) {
        long cacheTtlMs = SleuthConfigSchema.PERFORMANCE_CACHE_TTL_MS.read(config);
        int threadPoolCore = SleuthConfigSchema.PERFORMANCE_THREAD_POOL_CORE.read(config);
        int threadPoolMax = SleuthConfigSchema.PERFORMANCE_THREAD_POOL_MAX.read(config);
        if (threadPoolMax < threadPoolCore) {
            SleuthLogger.warn("Config normalized: performance.thread.pool.max < core, auto-adjusted to core");
            threadPoolMax = threadPoolCore;
        }

        int cmdExecCore = SleuthConfigSchema.PERFORMANCE_COMMAND_EXECUTOR_CORE.read(config);
        int cmdExecMax = SleuthConfigSchema.PERFORMANCE_COMMAND_EXECUTOR_MAX.read(config);
        if (cmdExecMax < cmdExecCore) {
            SleuthLogger.warn("Config normalized: performance.command.executor.max < core, auto-adjusted to core");
            cmdExecMax = cmdExecCore;
        }
        int cmdExecQueueCapacity = SleuthConfigSchema.PERFORMANCE_COMMAND_EXECUTOR_QUEUE_CAPACITY.read(config);

        int streamExecCore = SleuthConfigSchema.PERFORMANCE_COMMAND_STREAM_EXECUTOR_CORE.read(config);
        int streamExecMax = SleuthConfigSchema.PERFORMANCE_COMMAND_STREAM_EXECUTOR_MAX.read(config);
        if (streamExecMax < streamExecCore) {
            SleuthLogger.warn("Config normalized: performance.command.stream.executor.max < core, auto-adjusted to core");
            streamExecMax = streamExecCore;
        }
        int streamExecQueueCapacity = SleuthConfigSchema.PERFORMANCE_COMMAND_STREAM_EXECUTOR_QUEUE_CAPACITY.read(config);

        long timeoutMs = SleuthConfigSchema.PERFORMANCE_COMMAND_TIMEOUT_MS.read(config);
        long maxTimeoutMs = SleuthConfigSchema.PERFORMANCE_COMMAND_TIMEOUT_MAX_MS.read(config);
        if (maxTimeoutMs > 0 && timeoutMs > maxTimeoutMs) {
            ConfigOrigin origin = config.getOrigin("performance.command.timeout");
            if (origin == ConfigOrigin.FILE || origin == ConfigOrigin.SYSTEM_PROPERTY || origin == ConfigOrigin.RUNTIME_OVERRIDE) {
                SleuthLogger.warn("Config normalized: performance.command.timeout capped to performance.command.timeout.max (" + maxTimeoutMs + "ms)");
            }
            timeoutMs = maxTimeoutMs;
        }

        boolean forceGc = SleuthConfigSchema.PERFORMANCE_MAINTENANCE_FORCE_GC.read(config);

        return new PerformanceConfig(
            cacheTtlMs,
            threadPoolCore,
            threadPoolMax,
            cmdExecCore,
            cmdExecMax,
            cmdExecQueueCapacity,
            streamExecCore,
            streamExecMax,
            streamExecQueueCapacity,
            timeoutMs,
            maxTimeoutMs,
            forceGc
        );
    }

    private static JobsConfig parseJobs(ConfigView config) {
        int maxJobs = SleuthConfigSchema.JOBS_MAX.read(config);
        long ttlMs = SleuthConfigSchema.JOBS_TTL_MS.read(config);
        int outputMaxBytes = SleuthConfigSchema.JOBS_OUTPUT_MAX_BYTES.read(config);
        int maxRunning = SleuthConfigSchema.JOBS_MAX_RUNNING.read(config);
        int queueCapacity = SleuthConfigSchema.JOBS_QUEUE_CAPACITY.read(config);
        return new JobsConfig(maxJobs, ttlMs, outputMaxBytes, maxRunning, queueCapacity);
    }

    private static MonitoringConfig parseMonitoring(ConfigView config) {
        boolean metrics = SleuthConfigSchema.MONITORING_METRICS_ENABLED.read(config);
        boolean healthChecks = SleuthConfigSchema.MONITORING_HEALTH_CHECKS.read(config);
        long cleanup = SleuthConfigSchema.MONITORING_CACHE_CLEANUP_INTERVAL_MS.read(config);
        boolean jmx = SleuthConfigSchema.MONITORING_JMX_ENABLED.read(config);

        int watchQueue = SleuthConfigSchema.MONITORING_WATCH_QUEUE_CAPACITY.read(config);
        boolean watchDrop = SleuthConfigSchema.MONITORING_WATCH_DROP_ON_FULL.read(config);

        int traceQueue = SleuthConfigSchema.MONITORING_TRACE_QUEUE_CAPACITY.read(config);
        boolean traceDrop = SleuthConfigSchema.MONITORING_TRACE_DROP_ON_FULL.read(config);

        return new MonitoringConfig(
            metrics,
            healthChecks,
            cleanup,
            jmx,
            watchQueue,
            watchDrop,
            traceQueue,
            traceDrop
        );
    }

    private static LoggingConfig parseLogging(ConfigView config) {
        String level = SleuthConfigSchema.LOGGING_LEVEL.read(config);
        level = normalizeNonBlank(level, "INFO").toUpperCase(Locale.ROOT);

        boolean consoleEnabled = SleuthConfigSchema.LOGGING_CONSOLE_ENABLED.read(config);
        boolean auditEnabled = SleuthConfigSchema.LOGGING_AUDIT_ENABLED.read(config);
        boolean auditConsoleEnabled = SleuthConfigSchema.LOGGING_AUDIT_CONSOLE_ENABLED.read(config);

        String auditPath = SleuthConfigSchema.LOGGING_AUDIT_FILE_PATH.read(config);
        String securityPath = SleuthConfigSchema.LOGGING_SECURITY_FILE_PATH.read(config);

        LogPathResolver resolver = new LogPathResolver();
        String auditResolved = resolveLogPath(auditPath, resolver, "sleuth-audit.log");
        String securityResolved = resolveLogPath(securityPath, resolver, "sleuth-security.log");

        boolean performanceEnabled = SleuthConfigSchema.LOGGING_PERFORMANCE_ENABLED.read(config);

        return new LoggingConfig(
            level,
            consoleEnabled,
            auditEnabled,
            auditConsoleEnabled,
            auditResolved,
            securityResolved,
            performanceEnabled
        );
    }

    private static String resolveLogPath(String configured, LogPathResolver resolver, String baseName) {
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return resolver != null ? resolver.defaultLogPath(baseName) : configured;
    }

    private static PluginsConfig parsePlugins(ConfigView config) {
        boolean enabled = SleuthConfigSchema.PLUGINS_ENABLED.read(config);
        boolean serviceLoader = SleuthConfigSchema.PLUGINS_SERVICELOADER_ENABLED.read(config);
        String allowlist = SleuthConfigSchema.PLUGINS_ALLOWLIST_SHA256.read(config);
        String dir = normalizeNonBlank(SleuthConfigSchema.PLUGINS_DIRECTORY.read(config), "plugins");

        String rawStrategy = SleuthConfigSchema.PLUGINS_CONFLICT_STRATEGY.read(config);
        PluginsConfig.ConflictStrategy strategy = PluginsConfig.ConflictStrategy.fromWireName(
            rawStrategy,
            PluginsConfig.ConflictStrategy.PREFER_BUILTIN
        );

        return new PluginsConfig(enabled, serviceLoader, allowlist, dir, strategy);
    }

    private static String normalizeNonBlank(String v, String defaultValue) {
        if (v == null) {
            return defaultValue;
        }
        String s = v.trim();
        if (s.isEmpty()) {
            return defaultValue;
        }
        return s;
    }
}
