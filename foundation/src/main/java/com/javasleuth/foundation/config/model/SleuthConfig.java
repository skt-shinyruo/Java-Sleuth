package com.javasleuth.foundation.config.model;

/**
 * Java-Sleuth 的强类型配置聚合对象。
 *
 * <p>该对象用于在连接/会话等边界处一次性解析配置并做校验，避免核心链路散落
 * {@code "string.key" + default} 的读取模式造成默认值漂移与维护风险。</p>
 */
public final class SleuthConfig {
    private final ServerConfig server;
    private final ProtocolConfig protocol;
    private final SecurityConfig security;
    private final PerformanceConfig performance;
    private final JobsConfig jobs;
    private final MonitoringConfig monitoring;
    private final VmToolConfig vmTool;
    private final LoggingConfig logging;
    private final PluginsConfig plugins;

    public SleuthConfig(
        ServerConfig server,
        ProtocolConfig protocol,
        SecurityConfig security,
        PerformanceConfig performance,
        JobsConfig jobs,
        MonitoringConfig monitoring,
        VmToolConfig vmTool,
        LoggingConfig logging,
        PluginsConfig plugins
    ) {
        if (server == null) {
            throw new IllegalArgumentException("server is required");
        }
        if (protocol == null) {
            throw new IllegalArgumentException("protocol is required");
        }
        if (security == null) {
            throw new IllegalArgumentException("security is required");
        }
        if (performance == null) {
            throw new IllegalArgumentException("performance is required");
        }
        if (jobs == null) {
            throw new IllegalArgumentException("jobs is required");
        }
        if (monitoring == null) {
            throw new IllegalArgumentException("monitoring is required");
        }
        if (vmTool == null) {
            throw new IllegalArgumentException("vmTool is required");
        }
        if (logging == null) {
            throw new IllegalArgumentException("logging is required");
        }
        if (plugins == null) {
            throw new IllegalArgumentException("plugins is required");
        }
        this.server = server;
        this.protocol = protocol;
        this.security = security;
        this.performance = performance;
        this.jobs = jobs;
        this.monitoring = monitoring;
        this.vmTool = vmTool;
        this.logging = logging;
        this.plugins = plugins;
    }

    public ServerConfig server() {
        return server;
    }

    public ProtocolConfig protocol() {
        return protocol;
    }

    public SecurityConfig security() {
        return security;
    }

    public PerformanceConfig performance() {
        return performance;
    }

    public JobsConfig jobs() {
        return jobs;
    }

    public MonitoringConfig monitoring() {
        return monitoring;
    }

    public VmToolConfig vmTool() {
        return vmTool;
    }

    public LoggingConfig logging() {
        return logging;
    }

    public PluginsConfig plugins() {
        return plugins;
    }
}
