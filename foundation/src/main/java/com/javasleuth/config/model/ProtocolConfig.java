package com.javasleuth.config.model;

/**
 * 协议相关配置（framed/binary、streaming、大小上限）。
 */
public final class ProtocolConfig {
    public enum Mode {
        FRAMED("framed"),
        BINARY("binary");

        private final String wireName;

        Mode(String wireName) {
            this.wireName = wireName;
        }

        public String getWireName() {
            return wireName;
        }
    }

    private final Mode mode;
    private final boolean streamingEnabled;
    private final int frameMaxPayloadBytes;
    private final int textMaxLineBytes;

    public ProtocolConfig(Mode mode, boolean streamingEnabled, int frameMaxPayloadBytes, int textMaxLineBytes) {
        this.mode = mode;
        this.streamingEnabled = streamingEnabled;
        this.frameMaxPayloadBytes = frameMaxPayloadBytes;
        this.textMaxLineBytes = textMaxLineBytes;
    }

    public Mode getMode() {
        return mode;
    }

    public String getModeWireName() {
        return mode != null ? mode.getWireName() : "framed";
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

