package com.javasleuth.foundation.config.schema;

import com.javasleuth.foundation.config.ConfigOrigin;
import com.javasleuth.foundation.config.ConfigView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java-Sleuth 配置 Schema（SSOT）。
 *
 * <p>该类集中声明所有“支持的配置键”的类型、默认值、约束与关键失败策略。</p>
 */
public final class SleuthConfigSchema {
    private static final List<ConfigKey<?>> KEYS = new ArrayList<>();
    private static final Map<String, ConfigKey<?>> BY_KEY = new HashMap<>();

    private SleuthConfigSchema() {}

    private static <T> ConfigKey<T> register(ConfigKey<T> key) {
        if (key == null) {
            return null;
        }
        KEYS.add(key);
        BY_KEY.put(key.getKey(), key);
        return key;
    }

    // =============================================================================
    // Server
    // =============================================================================
    public static final ConfigKey<String> SERVER_BIND_ADDRESS = register(
        ConfigKey.stringKey("server.bind.address")
            .defaultValue("127.0.0.1")
            .nonBlank()
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> SERVER_PORT = register(
        ConfigKey.intKey("server.port")
            .defaultValue(3658)
            .longRange(1, 65535)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> SERVER_MAX_CONNECTIONS = register(
        ConfigKey.intKey("server.max.connections")
            .defaultValue(10)
            .longMin(0)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> SERVER_EXECUTOR_QUEUE_CAPACITY = register(
        ConfigKey.intKey("server.executor.queue.capacity")
            .defaultValue(50)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> SERVER_CONNECTION_TIMEOUT_MS = register(
        ConfigKey.intKey("server.connection.timeout")
            .defaultValue(30000)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> SERVER_SOCKET_TIMEOUT_MS = register(
        ConfigKey.intKey("server.socket.timeout")
            .defaultValue(1000)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    // =============================================================================
    // Performance
    // =============================================================================
    public static final ConfigKey<Long> PERFORMANCE_CACHE_TTL_MS = register(
        ConfigKey.longKey("performance.cache.ttl")
            .defaultValue(5000L)
            .longMin(0)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> PERFORMANCE_THREAD_POOL_CORE = register(
        ConfigKey.intKey("performance.thread.pool.core")
            .defaultValue(4)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> PERFORMANCE_THREAD_POOL_MAX = register(
        ConfigKey.intKey("performance.thread.pool.max")
            .defaultValue(16)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> PERFORMANCE_COMMAND_EXECUTOR_CORE = register(
        ConfigKey.intKey("performance.command.executor.core")
            .defaultValue(4)
            .longMin(1)
            .derivedDefault((config, origin, literalDefault) -> {
                // 派生默认：若 command executor 未显式配置，跟随 thread pool core。
                if (origin == ConfigOrigin.DEFAULT || origin == ConfigOrigin.UNKNOWN) {
                    return PERFORMANCE_THREAD_POOL_CORE.read(config);
                }
                return null;
            })
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> PERFORMANCE_COMMAND_EXECUTOR_MAX = register(
        ConfigKey.intKey("performance.command.executor.max")
            .defaultValue(16)
            .longMin(1)
            .derivedDefault((config, origin, literalDefault) -> {
                if (origin == ConfigOrigin.DEFAULT || origin == ConfigOrigin.UNKNOWN) {
                    return PERFORMANCE_THREAD_POOL_MAX.read(config);
                }
                return null;
            })
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> PERFORMANCE_COMMAND_EXECUTOR_QUEUE_CAPACITY = register(
        ConfigKey.intKey("performance.command.executor.queue.capacity")
            .defaultValue(200)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> PERFORMANCE_COMMAND_STREAM_EXECUTOR_CORE = register(
        ConfigKey.intKey("performance.command.stream.executor.core")
            .defaultValue(2)
            .longRange(1, 64)
            .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
            .build()
    );

    public static final ConfigKey<Integer> PERFORMANCE_COMMAND_STREAM_EXECUTOR_MAX = register(
        ConfigKey.intKey("performance.command.stream.executor.max")
            .defaultValue(4)
            .longRange(1, 64)
            .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
            .build()
    );

    public static final ConfigKey<Integer> PERFORMANCE_COMMAND_STREAM_EXECUTOR_QUEUE_CAPACITY = register(
        ConfigKey.intKey("performance.command.stream.executor.queue.capacity")
            .defaultValue(32)
            .longRange(1, 10000)
            .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
            .build()
    );

    public static final ConfigKey<Long> PERFORMANCE_COMMAND_STREAM_STARTUP_TIMEOUT_MS = register(
        ConfigKey.longKey("performance.command.stream.startup.timeout.ms")
            .defaultValue(3000L)
            .longRange(100, 60000)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Long> PERFORMANCE_COMMAND_TIMEOUT_MS = register(
        ConfigKey.longKey("performance.command.timeout")
            .defaultValue(60000L)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Long> PERFORMANCE_COMMAND_TIMEOUT_MAX_MS = register(
        ConfigKey.longKey("performance.command.timeout.max")
            .defaultValue(300000L)
            .longMin(0)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> PERFORMANCE_MAINTENANCE_FORCE_GC = register(
        ConfigKey.booleanKey("performance.maintenance.force_gc")
            .defaultValue(Boolean.FALSE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    // =============================================================================
    // Enhancement
    // =============================================================================
    public static final ConfigKey<Long> ENHANCEMENT_FAILURE_COOLDOWN_MS = register(
        ConfigKey.longKey("enhancement.failure.cooldown.ms")
            .defaultValue(30000L)
            .longMin(0)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Long> ENHANCEMENT_FAILURE_LOG_INTERVAL_MS = register(
        ConfigKey.longKey("enhancement.failure.log.interval.ms")
            .defaultValue(60000L)
            .longMin(0)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    // =============================================================================
    // Jobs
    // =============================================================================
    public static final ConfigKey<Integer> JOBS_MAX = register(
        ConfigKey.intKey("jobs.max")
            .defaultValue(200)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Long> JOBS_TTL_MS = register(
        ConfigKey.longKey("jobs.ttl.ms")
            .defaultValue(3600000L)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> JOBS_OUTPUT_MAX_BYTES = register(
        ConfigKey.intKey("jobs.output.max.bytes")
            .defaultValue(262144)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> JOBS_MAX_RUNNING = register(
        ConfigKey.intKey("jobs.max.running")
            .defaultValue(4)
            .longRange(1, 64)
            .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
            .build()
    );

    public static final ConfigKey<Integer> JOBS_QUEUE_CAPACITY = register(
        ConfigKey.intKey("jobs.queue.capacity")
            .defaultValue(20)
            .longRange(1, 10000)
            .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
            .build()
    );

    // =============================================================================
    // Security
    // =============================================================================
    public static final ConfigKey<Boolean> SECURITY_INPUT_VALIDATION = register(
        ConfigKey.booleanKey("security.input.validation")
            .defaultValue(Boolean.TRUE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> SECURITY_AUDIT_LOGGING = register(
        ConfigKey.booleanKey("security.audit.logging")
            .defaultValue(Boolean.TRUE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> SECURITY_MAX_COMMAND_LENGTH = register(
        ConfigKey.intKey("security.max.command.length")
            .defaultValue(1000)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<String> SECURITY_ALLOWED_COMMANDS = register(
        ConfigKey.stringKey("security.allowed.commands")
            .defaultValue("*")
            .nonBlank()
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> SECURITY_DANGEROUS_CONFIRM_ENABLED = register(
        ConfigKey.booleanKey("security.dangerous.confirm.enabled")
            .defaultValue(Boolean.FALSE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Long> SECURITY_DANGEROUS_CONFIRM_TTL_MS = register(
        ConfigKey.longKey("security.dangerous.confirm.ttl.ms")
            .defaultValue(60000L)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> SECURITY_DANGEROUS_CONFIRM_TOKEN_BYTES = register(
        ConfigKey.intKey("security.dangerous.confirm.token.bytes")
            .defaultValue(12)
            .longRange(1, 64)
            .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
            .build()
    );

    public static final ConfigKey<Integer> SECURITY_DANGEROUS_CONFIRM_CACHE_SIZE = register(
        ConfigKey.intKey("security.dangerous.confirm.cache.size")
            .defaultValue(2000)
            .longMin(0)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> SECURITY_IMPACT_HIGH_CONFIRM_ENABLED = register(
        ConfigKey.booleanKey("security.impact.high.confirm.enabled")
            .defaultValue(Boolean.FALSE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> SECURITY_IMPACT_HIGH_CONCURRENT_LIMIT = register(
        ConfigKey.intKey("security.impact.high.concurrent.limit")
            .defaultValue(1)
            .longMin(0)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> SECURITY_AUTHORIZATION_ENABLED = register(
        ConfigKey.booleanKey("security.authorization.enabled")
            .defaultValue(Boolean.FALSE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> SECURITY_ANONYMOUS_VIEWER = register(
        ConfigKey.booleanKey("security.anonymous.viewer")
            .defaultValue(Boolean.TRUE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> SECURITY_AUTH_PASSWORD_ENABLED = register(
        ConfigKey.booleanKey("security.auth.password.enabled")
            .defaultValue(Boolean.FALSE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<String> SECURITY_AUTH_ADMIN_PASSWORD = register(
        ConfigKey.stringKey("security.auth.admin.password")
            .defaultValue("")
            .sensitive()
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<String> SECURITY_AUTH_OPERATOR_PASSWORD = register(
        ConfigKey.stringKey("security.auth.operator.password")
            .defaultValue("")
            .sensitive()
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<String> SECURITY_AUTH_VIEWER_PASSWORD = register(
        ConfigKey.stringKey("security.auth.viewer.password")
            .defaultValue("")
            .sensitive()
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    // =============================================================================
    // Monitoring
    // =============================================================================
    public static final ConfigKey<Boolean> MONITORING_METRICS_ENABLED = register(
        ConfigKey.booleanKey("monitoring.metrics.enabled")
            .defaultValue(Boolean.TRUE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> MONITORING_HEALTH_CHECKS = register(
        ConfigKey.booleanKey("monitoring.health.checks")
            .defaultValue(Boolean.TRUE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Long> MONITORING_CACHE_CLEANUP_INTERVAL_MS = register(
        ConfigKey.longKey("monitoring.cache.cleanup.interval")
            .defaultValue(300000L)
            .longMin(0)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> MONITORING_JMX_ENABLED = register(
        ConfigKey.booleanKey("monitoring.jmx.enabled")
            .defaultValue(Boolean.TRUE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> MONITORING_WATCH_QUEUE_CAPACITY = register(
        ConfigKey.intKey("monitoring.watch.queue.capacity")
            .defaultValue(1000)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> MONITORING_WATCH_DROP_ON_FULL = register(
        ConfigKey.booleanKey("monitoring.watch.drop.on.full")
            .defaultValue(Boolean.TRUE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> MONITORING_TRACE_QUEUE_CAPACITY = register(
        ConfigKey.intKey("monitoring.trace.queue.capacity")
            .defaultValue(2000)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> MONITORING_TRACE_DROP_ON_FULL = register(
        ConfigKey.booleanKey("monitoring.trace.drop.on.full")
            .defaultValue(Boolean.TRUE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    // =============================================================================
    // VmTool
    // =============================================================================
    public static final ConfigKey<Integer> VMTOOL_TRACK_MAX_ENTRIES = register(
        ConfigKey.intKey("vmtool.track.max.entries")
            .defaultValue(500)
            .longRange(1, 100000)
            .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
            .build()
    );

    public static final ConfigKey<Integer> VMTOOL_TRACK_CLASS_LIMIT = register(
        ConfigKey.intKey("vmtool.track.class.limit")
            .defaultValue(50)
            .longRange(1, 10000)
            .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
            .build()
    );

    // =============================================================================
    // Logging
    // =============================================================================
    public static final ConfigKey<String> LOGGING_LEVEL = register(
        ConfigKey.stringKey("logging.level")
            .defaultValue("INFO")
            .allowedStrings("TRACE", "DEBUG", "INFO", "WARN", "ERROR")
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> LOGGING_CONSOLE_ENABLED = register(
        ConfigKey.booleanKey("logging.console.enabled")
            .defaultValue(Boolean.TRUE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> LOGGING_AUDIT_ENABLED = register(
        ConfigKey.booleanKey("logging.audit.enabled")
            .defaultValue(Boolean.TRUE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> LOGGING_AUDIT_CONSOLE_ENABLED = register(
        ConfigKey.booleanKey("logging.audit.console.enabled")
            .defaultValue(Boolean.FALSE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<String> LOGGING_AUDIT_FILE_PATH = register(
        ConfigKey.stringKey("logging.audit.file.path")
            .defaultValue("")
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<String> LOGGING_SECURITY_FILE_PATH = register(
        ConfigKey.stringKey("logging.security.file.path")
            .defaultValue("")
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> LOGGING_PERFORMANCE_ENABLED = register(
        ConfigKey.booleanKey("logging.performance.enabled")
            .defaultValue(Boolean.FALSE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    // =============================================================================
    // Protocol
    // =============================================================================
    public static final ConfigKey<Boolean> PROTOCOL_STREAMING_ENABLED = register(
        ConfigKey.booleanKey("protocol.streaming.enabled")
            .defaultValue(Boolean.TRUE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> PROTOCOL_FRAME_MAX_PAYLOAD = register(
        ConfigKey.intKey("protocol.frame.max.payload")
            .defaultValue(4096)
            .longMin(1)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Integer> PROTOCOL_TEXT_MAX_LINE_BYTES = register(
        ConfigKey.intKey("protocol.text.max.line.bytes")
            .defaultValue(8192)
            .longMin(1)
            .derivedDefault((config, origin, literalDefault) -> {
                // 派生默认：若 text.max.line.bytes 未显式配置，则跟随 frame.max.payload（并保底 8192）。
                if (origin == ConfigOrigin.DEFAULT || origin == ConfigOrigin.UNKNOWN) {
                    int frame = PROTOCOL_FRAME_MAX_PAYLOAD.read(config);
                    return Math.max(8192, frame * 2);
                }
                return null;
            })
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    // =============================================================================
    // Plugins
    // =============================================================================
    public static final ConfigKey<Boolean> PLUGINS_ENABLED = register(
        ConfigKey.booleanKey("plugins.enabled")
            .defaultValue(Boolean.FALSE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<Boolean> PLUGINS_SERVICELOADER_ENABLED = register(
        ConfigKey.booleanKey("plugins.serviceloader.enabled")
            .defaultValue(Boolean.FALSE)
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<String> PLUGINS_ALLOWLIST_SHA256 = register(
        ConfigKey.stringKey("plugins.allowlist.sha256")
            .defaultValue("")
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<String> PLUGINS_DIRECTORY = register(
        ConfigKey.stringKey("plugins.directory")
            .defaultValue("plugins")
            .nonBlank()
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    public static final ConfigKey<String> PLUGINS_CONFLICT_STRATEGY = register(
        ConfigKey.stringKey("plugins.conflict.strategy")
            .defaultValue("prefer-builtin")
            .allowedStrings("prefer-builtin", "prefer-plugin")
            .failurePolicy(ConfigKey.FailurePolicy.WARN_AND_FALLBACK)
            .build()
    );

    // =============================================================================
    // Forbidden keys (removed/unsupported)
    // =============================================================================
    private static final Set<String> FORBIDDEN_KEYS = forbiddenKeysInternal();

    private static Set<String> forbiddenKeysInternal() {
        Set<String> keys = new HashSet<>();
        // Removed: HMAC request signing mode.
        keys.add("security.mode");
        keys.add("security.hmac.secret");
        keys.add("security.hmac.secret.autogen.on.loopback");
        keys.add("security.hmac.secret.autogen.print");
        keys.add("security.hmac.timestamp.window.ms");
        keys.add("security.hmac.nonce.cache.size");
        keys.add("security.bootstrap.hmac.on.attach");
        keys.add("security.bootstrap.hmac.secret.bytes");
        keys.add("security.hmac.session.role");

        // Removed: legacy protocol flags.
        keys.add("protocol.mode");
        keys.add("protocol.handshake.enabled");
        keys.add("protocol.text.end.marker.enabled");
        return keys;
    }

    public static Set<String> forbiddenKeys() {
        return Collections.unmodifiableSet(FORBIDDEN_KEYS);
    }

    // =============================================================================
    // Accessors
    // =============================================================================
    public static List<ConfigKey<?>> keys() {
        return Collections.unmodifiableList(KEYS);
    }

    public static ConfigKey<?> byKey(String key) {
        if (key == null) {
            return null;
        }
        return BY_KEY.get(key);
    }
}
