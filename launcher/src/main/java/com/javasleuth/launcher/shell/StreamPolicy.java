package com.javasleuth.launcher.shell;

/**
 * 流式输出策略（客户端侧）。
 *
 * <p>用于决定某条命令是否应使用 STREAM/stream=true 方式执行。</p>
 */
public interface StreamPolicy {
    boolean isStreamingCommand(String command);
}

