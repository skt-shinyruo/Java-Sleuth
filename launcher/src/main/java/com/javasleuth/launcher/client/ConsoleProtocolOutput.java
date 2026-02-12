package com.javasleuth.launcher.client;

import java.io.PrintStream;

/**
 * 默认控制台输出实现。
 */
public final class ConsoleProtocolOutput implements ProtocolOutput {
    private final PrintStream out;
    private final PrintStream err;

    public ConsoleProtocolOutput(PrintStream out, PrintStream err) {
        this.out = out != null ? out : System.out;
        this.err = err != null ? err : System.err;
    }

    @Override
    public void onStdoutLine(String line) {
        out.println(line);
    }

    @Override
    public void onStderrLine(String line) {
        err.println(line);
    }

    @Override
    public void onStdoutChunk(String chunk) {
        out.print(chunk);
    }

    @Override
    public void onStderrChunk(String chunk) {
        err.print(chunk);
    }
}

