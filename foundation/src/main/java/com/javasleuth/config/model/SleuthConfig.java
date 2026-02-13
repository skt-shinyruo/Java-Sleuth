package com.javasleuth.config.model;

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

    public SleuthConfig(ServerConfig server, ProtocolConfig protocol, SecurityConfig security) {
        if (server == null) {
            throw new IllegalArgumentException("server is required");
        }
        if (protocol == null) {
            throw new IllegalArgumentException("protocol is required");
        }
        if (security == null) {
            throw new IllegalArgumentException("security is required");
        }
        this.server = server;
        this.protocol = protocol;
        this.security = security;
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
}

