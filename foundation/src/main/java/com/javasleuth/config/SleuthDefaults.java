package com.javasleuth.config;

import java.util.Properties;

/**
 * Java-Sleuth 的集中默认配置。
 *
 * <p>该类用于把“默认值”的事实来源收敛到单处，避免在多个位置手写默认导致漂移。</p>
 *
 * <p>注意：默认资源 {@code /sleuth-default.properties} 仍是运维可读的 SSOT；
 * 当默认资源无法加载时，{@link ConfigLoader} 会回退到这里的默认集合。</p>
 */
public final class SleuthDefaults {
    private SleuthDefaults() {
    }

    public static void apply(Properties properties) {
        if (properties == null) {
            return;
        }

        // Server configuration
        properties.setProperty("server.bind.address", "127.0.0.1");
        properties.setProperty("server.port", "3658");
        properties.setProperty("server.max.connections", "10");
        // Client accept/handling executor queue (backpressure + memory bound)
        properties.setProperty("server.executor.queue.capacity", "50");
        properties.setProperty("server.connection.timeout", "30000");
        properties.setProperty("server.socket.timeout", "1000");

        // Performance configuration
        properties.setProperty("performance.cache.ttl", "5000");
        properties.setProperty("performance.thread.pool.core", "4");
        properties.setProperty("performance.thread.pool.max", "16");
        // Dedicated command execution executor (avoid unbounded newCachedThreadPool)
        properties.setProperty("performance.command.executor.core", properties.getProperty("performance.thread.pool.core", "4"));
        properties.setProperty("performance.command.executor.max", properties.getProperty("performance.thread.pool.max", "16"));
        properties.setProperty("performance.command.executor.queue.capacity", "200");
        properties.setProperty("performance.command.timeout", "60000");
        properties.setProperty("performance.command.timeout.max", "300000");
        properties.setProperty("performance.maintenance.force_gc", "false");

        // Enhancement failure strategy (cooldown + retry, avoid silent disable)
        properties.setProperty("enhancement.failure.cooldown.ms", "30000");
        properties.setProperty("enhancement.failure.log.interval.ms", "60000");

        // Job retention configuration
        properties.setProperty("jobs.max", "200");
        properties.setProperty("jobs.ttl.ms", "3600000");
        properties.setProperty("jobs.output.max.bytes", "262144");
        // Background job execution limits (backpressure + stability)
        properties.setProperty("jobs.max.running", "4");
        properties.setProperty("jobs.queue.capacity", "20");

        // Security configuration
        properties.setProperty("security.input.validation", "true");
        properties.setProperty("security.audit.logging", "true");
        properties.setProperty("security.max.command.length", "1000");
        properties.setProperty("security.allowed.commands", "*");
        properties.setProperty("security.authorization.enabled", "false");
        properties.setProperty("security.anonymous.viewer", "true");
        properties.setProperty("security.mode", "off");
        properties.setProperty("security.hmac.secret", "");
        // Loopback self-contained startup: auto-generate temporary secret if empty.
        properties.setProperty("security.hmac.secret.autogen.on.loopback", "true");
        properties.setProperty("security.hmac.secret.autogen.print", "true");
        properties.setProperty("security.hmac.timestamp.window.ms", "30000");
        properties.setProperty("security.hmac.nonce.cache.size", "10000");
        properties.setProperty("security.dangerous.confirm.enabled", "false");
        properties.setProperty("security.dangerous.confirm.ttl.ms", "60000");
        properties.setProperty("security.dangerous.confirm.token.bytes", "12");
        properties.setProperty("security.dangerous.confirm.cache.size", "2000");
        // High impact commands governance (non-privileged but performance-risky operations)
        properties.setProperty("security.impact.high.confirm.enabled", "false");
        properties.setProperty("security.impact.high.concurrent.limit", "1");
        properties.setProperty("security.bootstrap.hmac.on.attach", "false");
        properties.setProperty("security.bootstrap.hmac.secret.bytes", "32");
        properties.setProperty("security.hmac.session.role", "operator");
        properties.setProperty("security.auth.password.enabled", "false");
        properties.setProperty("security.auth.admin.password", "");
        properties.setProperty("security.auth.operator.password", "");
        properties.setProperty("security.auth.viewer.password", "");

        // Protocol configuration
        properties.setProperty("protocol.mode", "framed");
        properties.setProperty("protocol.streaming.enabled", "true");
        properties.setProperty("protocol.frame.max.payload", "4096");
        properties.setProperty("protocol.text.max.line.bytes", "8192");

        // Plugin configuration
        properties.setProperty("plugins.enabled", "false");
        // When disabled, do not load CommandProvider from the target application's classpath by default.
        properties.setProperty("plugins.serviceloader.enabled", "false");
        properties.setProperty("plugins.allowlist.sha256", "");
        properties.setProperty("plugins.directory", "plugins");
        properties.setProperty("plugins.conflict.strategy", "prefer-builtin");

        // Monitoring queue configuration
        properties.setProperty("monitoring.watch.queue.capacity", "1000");
        properties.setProperty("monitoring.watch.drop.on.full", "true");
        properties.setProperty("monitoring.trace.queue.capacity", "2000");
        properties.setProperty("monitoring.trace.drop.on.full", "true");
        properties.setProperty("monitoring.trace.sample.rate", "0.1");
        properties.setProperty("monitoring.monitor.sample.rate", "1.0");

        // Monitoring configuration
        properties.setProperty("monitoring.metrics.enabled", "true");
        properties.setProperty("monitoring.health.checks", "true");
        properties.setProperty("monitoring.cache.cleanup.interval", "300000");
        properties.setProperty("monitoring.jmx.enabled", "true");

        // Logging configuration
        properties.setProperty("logging.level", "INFO");
        // Console logging (stderr) is useful for local troubleshooting; tests may override via -Dsleuth.logging.level=ERROR.
        properties.setProperty("logging.console.enabled", "true");
        properties.setProperty("logging.audit.enabled", "true");
        properties.setProperty("logging.audit.console.enabled", "false");
        properties.setProperty("logging.audit.file.path", "");
        properties.setProperty("logging.security.file.path", "");
        // Performance/health logging to stdout/stderr is noisy in production; keep it opt-in.
        properties.setProperty("logging.performance.enabled", "false");
    }
}

