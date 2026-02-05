package com.javasleuth.command.server.protocol;

import com.javasleuth.command.protocol.BinaryFrame;
import com.javasleuth.command.protocol.BinaryFrameCodec;
import java.io.DataOutputStream;
import java.io.IOException;

final class BinaryReplyChannel implements CommandReplyChannel {
    private final DataOutputStream out;
    private final int maxPayloadBytes;

    BinaryReplyChannel(DataOutputStream out, int maxPayloadBytes) {
        this.out = out;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @Override
    public void sendData(String data) throws IOException {
        BinaryFrameCodec.writeFrame(out, BinaryFrame.data(data != null ? data : ""), maxPayloadBytes);
    }

    @Override
    public void sendError(String message) throws IOException {
        BinaryFrameCodec.writeFrame(out, BinaryFrame.err(message != null ? message : "Unknown error"), maxPayloadBytes);
        BinaryFrameCodec.writeFrame(out, BinaryFrame.end(), maxPayloadBytes);
    }

    @Override
    public void end() throws IOException {
        BinaryFrameCodec.writeFrame(out, BinaryFrame.end(), maxPayloadBytes);
    }

    @Override
    public void sendLegacyEndMarker() {
        // Not applicable for binary protocol.
    }
}

