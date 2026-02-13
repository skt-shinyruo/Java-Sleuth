package com.javasleuth.config.model;

/**
 * 服务器侧相关配置（监听、连接治理、基础超时）。
 */
public final class ServerConfig {
    private final String bindAddress;
    private final int port;
    private final int maxConnections;
    private final int executorQueueCapacity;
    private final int connectionTimeoutMs;
    private final int socketTimeoutMs;

    public ServerConfig(
        String bindAddress,
        int port,
        int maxConnections,
        int executorQueueCapacity,
        int connectionTimeoutMs,
        int socketTimeoutMs
    ) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.maxConnections = maxConnections;
        this.executorQueueCapacity = executorQueueCapacity;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.socketTimeoutMs = socketTimeoutMs;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public int getPort() {
        return port;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public int getExecutorQueueCapacity() {
        return executorQueueCapacity;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }
}

