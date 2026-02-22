package com.javasleuth.core.command.server.protocol;

import com.javasleuth.foundation.command.protocol.Frame;
import com.javasleuth.foundation.command.protocol.FrameCodec;
import java.io.IOException;
import java.io.OutputStream;

final class FramedReplyChannel implements CommandReplyChannel {
    private final OutputStream out;
    private final int maxPayloadBytes;

    FramedReplyChannel(OutputStream out, int maxPayloadBytes) {
        this.out = out;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @Override
    public void sendData(String data) throws IOException {
        FrameCodec.writeFrame(out, Frame.data(data != null ? data : ""), maxPayloadBytes);
    }

    @Override
    public void sendError(String message) throws IOException {
        FrameCodec.writeFrame(out, Frame.err(message != null ? message : "Unknown error"), maxPayloadBytes);
        FrameCodec.writeFrame(out, Frame.end(), maxPayloadBytes);
    }

    @Override
    public void end() throws IOException {
        FrameCodec.writeFrame(out, Frame.end(), maxPayloadBytes);
    }
}
