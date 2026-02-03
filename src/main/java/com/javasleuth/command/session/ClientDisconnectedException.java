package com.javasleuth.command.session;

/**
 * 用于在服务端写回客户端失败时，快速中断当前命令执行并触发资源清理。
 *
 * <p>典型场景：客户端断开连接 / 端口转发中断 / 代理重置连接。</p>
 */
public class ClientDisconnectedException extends RuntimeException {
    public ClientDisconnectedException(String message) {
        super(message);
    }
}

