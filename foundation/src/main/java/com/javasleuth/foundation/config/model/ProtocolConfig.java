package com.javasleuth.foundation.config.model;

/**
 * 协议相关配置（streaming、大小上限）。
 */
public final class ProtocolConfig {
    private final boolean streamingEnabled;
    private final int frameMaxPayloadBytes;
    private final int textMaxLineBytes;

    public ProtocolConfig(boolean streamingEnabled, int frameMaxPayloadBytes, int textMaxLineBytes) {
        this.streamingEnabled = streamingEnabled;
        this.frameMaxPayloadBytes = frameMaxPayloadBytes;
        this.textMaxLineBytes = textMaxLineBytes;
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public int getFrameMaxPayloadBytes() {
        return frameMaxPayloadBytes;
    }

    public int getTextMaxLineBytes() {
        return textMaxLineBytes;
    }
}
