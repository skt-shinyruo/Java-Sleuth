package com.javasleuth.command;

public interface StreamSink {
    void send(String data);
    void close(String summary);
    void error(String message);
}

