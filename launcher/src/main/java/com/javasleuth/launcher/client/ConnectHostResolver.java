package com.javasleuth.launcher.client;

/**
 * 连接地址解析。
 *
 * <p>当 agent bind 在 0.0.0.0/:: 等“不可连接的地址”时，launcher 在本机 attach 场景下应回退到 127.0.0.1。</p>
 */
public final class ConnectHostResolver {
    private ConnectHostResolver() {}

    public static String resolveConnectHost(String bindAddress) {
        if (bindAddress == null) {
            return "127.0.0.1";
        }
        String v = bindAddress.trim();
        if (v.isEmpty()) {
            return "127.0.0.1";
        }
        String lower = v.toLowerCase();
        // Unspecified bind addresses are not connectable; use loopback for local attach sessions.
        if ("0.0.0.0".equals(lower) || "::".equals(lower) || "0:0:0:0:0:0:0:0".equals(lower)) {
            return "127.0.0.1";
        }
        return v;
    }
}

