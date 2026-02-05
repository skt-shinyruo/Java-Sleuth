package com.javasleuth.command.server.protocol;

import com.javasleuth.command.protocol.Utf8LineCodec;
import java.io.IOException;
import java.io.OutputStream;

final class TextReplyChannel implements CommandReplyChannel {
    private final OutputStream out;

    TextReplyChannel(OutputStream out) {
        this.out = out;
    }

    @Override
    public void sendData(String data) throws IOException {
        Utf8LineCodec.writeLine(out, data != null ? data : "", true);
    }

    @Override
    public void sendError(String message) throws IOException {
        Utf8LineCodec.writeLine(out, message != null ? message : "Unknown error", true);
    }

    @Override
    public void end() {
        // Legacy text protocol has no explicit end frame for non-stream commands.
    }

    @Override
    public void sendLegacyEndMarker() throws IOException {
        Utf8LineCodec.writeLine(out, "END", true);
    }
}

