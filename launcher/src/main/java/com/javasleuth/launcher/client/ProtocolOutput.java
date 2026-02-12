package com.javasleuth.launcher.client;

/**
 * 协议输出回调。
 *
 * <p>用于将“协议读写”与“输出呈现（console/log/测试捕获）”解耦。</p>
 */
public interface ProtocolOutput {
    void onStdoutLine(String line);

    void onStderrLine(String line);

    void onStdoutChunk(String chunk);

    void onStderrChunk(String chunk);
}

