package com.javasleuth.command.server.protocol;

import java.io.IOException;

interface CommandReplyChannel {
    void sendData(String data) throws IOException;

    void sendError(String message) throws IOException;

    void end() throws IOException;

    /**
     * Legacy (non-framed) clients may rely on an explicit END marker to avoid client-side
     * read-timeout heuristics. Framed/binary channels should no-op.
     */
    void sendLegacyEndMarker() throws IOException;
}

