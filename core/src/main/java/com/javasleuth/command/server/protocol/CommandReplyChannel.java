package com.javasleuth.command.server.protocol;

import java.io.IOException;

interface CommandReplyChannel {
    void sendData(String data) throws IOException;

    void sendError(String message) throws IOException;

    void end() throws IOException;
}
