package com.javasleuth.launcher.client;

import com.javasleuth.foundation.command.protocol.KvLineCodec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 客户端握手协商工具。
 *
 * <p>负责构建 HELLO 与解析 CONFIG 的 key/value；不负责 socket 读写。</p>
 */
public final class HandshakeClient {
    private HandshakeClient() {}

    public static String generateConnId() {
        byte[] bytes = new byte[12];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String buildHelloLine(String preferredProtocol, String connId) {
        String requested = preferredProtocol != null ? preferredProtocol.trim().toLowerCase() : "binary";
        if (!"binary".equals(requested)) {
            requested = "binary";
        }
        String base = "HELLO v=1 protocols=binary protocol=" + requested;
        if (connId != null && !connId.trim().isEmpty()) {
            return base + " connId=" + connId.trim();
        }
        return base;
    }

    public static HandshakeConfig parseConfigLine(String configLine) {
        if (configLine == null) {
            return null;
        }
        String line = configLine.trim();
        if (!line.startsWith("CONFIG")) {
            return null;
        }

        Map<String, String> kv = KvLineCodec.parseAfterVerb(line);
        String protocol = kv.get("protocol");
        if (protocol != null) {
            protocol = protocol.trim().toLowerCase();
        }

        boolean streamingEnabled = false;
        if (kv.containsKey("streaming")) {
            streamingEnabled = Boolean.parseBoolean(kv.get("streaming"));
        }

        Integer maxPayload = null;
        if (kv.containsKey("maxpayload")) {
            maxPayload = parseIntSafe(kv.get("maxpayload"));
        }

        String connId = kv.get("connid");
        if (connId != null) {
            connId = connId.trim();
        }

        return new HandshakeConfig(protocol, streamingEnabled, maxPayload, connId);
    }


    private static Integer parseIntSafe(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
