package com.javasleuth.launcher.client;

/**
 * HELLO/CONFIG 握手协商结果。
 */
public final class HandshakeConfig {
    private final String protocol;
    private final boolean streamingEnabled;
    private final Integer maxPayloadBytes;
    private final String connId;

    public HandshakeConfig(String protocol, boolean streamingEnabled, Integer maxPayloadBytes, String connId) {
        this.protocol = protocol;
        this.streamingEnabled = streamingEnabled;
        this.maxPayloadBytes = maxPayloadBytes;
        this.connId = connId;
    }

    public String getProtocol() {
        return protocol;
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public Integer getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public String getConnId() {
        return connId;
    }
}

