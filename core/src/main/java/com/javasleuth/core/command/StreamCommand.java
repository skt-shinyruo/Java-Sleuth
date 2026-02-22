package com.javasleuth.core.command;

public interface StreamCommand extends Command {
    void executeStream(String[] args, StreamSink sink) throws Exception;
}
