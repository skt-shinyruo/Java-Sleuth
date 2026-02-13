package com.javasleuth.config.model;

import com.javasleuth.config.ConfigOrigin;
import com.javasleuth.config.ConfigView;
import java.util.Locale;

/**
 * 强类型配置解析器：从 {@link ConfigView} 读取并产出 {@link SleuthConfig}。
 *
 * <p>设计目标：</p>
 * <ul>
 *   <li>集中管理 key/默认值/校验与归一化规则</li>
 *   <li>在连接/会话等边界处一次性解析，避免运行时散落的默认值漂移</li>
 * </ul>
 */
public final class SleuthConfigParser {
    private SleuthConfigParser() {
    }

    public static SleuthConfig parse(ConfigView config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }

        ServerConfig server = parseServer(config);
        ProtocolConfig protocol = parseProtocol(config);
        SecurityConfig security = parseSecurity(config);

        return new SleuthConfig(server, protocol, security);
    }

    private static ServerConfig parseServer(ConfigView config) {
        String bind = normalizeNonBlank(config.getString("server.bind.address", "127.0.0.1"), "127.0.0.1");

        int port = config.getInt("server.port", 3658);
        if (port <= 0 || port > 65535) {
            port = 3658;
        }

        int maxConn = config.getInt("server.max.connections", 10);
        if (maxConn <= 0) {
            maxConn = 10;
        }

        int queueCap = config.getInt("server.executor.queue.capacity", 50);
        if (queueCap <= 0) {
            queueCap = 50;
        }

        int connTimeout = config.getInt("server.connection.timeout", 30000);
        if (connTimeout <= 0) {
            connTimeout = 30000;
        }

        int sockTimeout = config.getInt("server.socket.timeout", 1000);
        if (sockTimeout <= 0) {
            sockTimeout = 1000;
        }

        return new ServerConfig(bind, port, maxConn, queueCap, connTimeout, sockTimeout);
    }

    private static ProtocolConfig parseProtocol(ConfigView config) {
        String rawMode = config.getString("protocol.mode", "framed");
        ProtocolConfig.Mode mode = parseProtocolMode(rawMode, config.getOrigin("protocol.mode"));

        boolean streaming = config.getBoolean("protocol.streaming.enabled", true);

        int frameMaxPayload = config.getInt("protocol.frame.max.payload", 4096);
        if (frameMaxPayload <= 0) {
            frameMaxPayload = 4096;
        }

        int derivedLineMax = Math.max(8192, frameMaxPayload * 2);
        int textMaxLineBytes;
        ConfigOrigin lineOrigin = config.getOrigin("protocol.text.max.line.bytes");
        if (lineOrigin == ConfigOrigin.FILE
            || lineOrigin == ConfigOrigin.SYSTEM_PROPERTY
            || lineOrigin == ConfigOrigin.RUNTIME_OVERRIDE) {
            textMaxLineBytes = config.getInt("protocol.text.max.line.bytes", derivedLineMax);
            if (textMaxLineBytes <= 0) {
                textMaxLineBytes = derivedLineMax;
            }
        } else {
            // 默认来源（或缺失）：使用派生默认，避免仅调整 frameMaxPayload 时产生不一致。
            textMaxLineBytes = derivedLineMax;
        }

        return new ProtocolConfig(mode, streaming, frameMaxPayload, textMaxLineBytes);
    }

    private static SecurityConfig parseSecurity(ConfigView config) {
        String raw = config.getString("security.mode", "off");
        SecurityConfig.Mode mode = parseSecurityMode(raw, config.getOrigin("security.mode"));

        boolean authorization = config.getBoolean("security.authorization.enabled", false);
        boolean anonymousViewer = config.getBoolean("security.anonymous.viewer", true);
        String sessionRole = normalizeNonBlank(config.getString("security.hmac.session.role", "operator"), "operator");

        return new SecurityConfig(mode, authorization, anonymousViewer, sessionRole);
    }

    private static ProtocolConfig.Mode parseProtocolMode(String raw, ConfigOrigin origin) {
        String v = normalizeNonBlank(raw, "framed").toLowerCase(Locale.ROOT);
        if ("framed".equals(v)) {
            return ProtocolConfig.Mode.FRAMED;
        }
        if ("binary".equals(v)) {
            return ProtocolConfig.Mode.BINARY;
        }
        // 协议模式属于关键兼容边界：若用户显式配置了非法值，直接 fail-fast。
        if (origin == ConfigOrigin.FILE || origin == ConfigOrigin.SYSTEM_PROPERTY || origin == ConfigOrigin.RUNTIME_OVERRIDE) {
            throw new IllegalArgumentException("Unsupported protocol.mode: " + raw + " (allowed: framed|binary)");
        }
        return ProtocolConfig.Mode.FRAMED;
    }

    private static SecurityConfig.Mode parseSecurityMode(String raw, ConfigOrigin origin) {
        String v = normalizeNonBlank(raw, "off").toLowerCase(Locale.ROOT);
        if ("off".equals(v)) {
            return SecurityConfig.Mode.OFF;
        }
        if ("hmac".equals(v)) {
            return SecurityConfig.Mode.HMAC;
        }
        // 安全模式显式配置错误不应静默降级到 off（否则可能造成误暴露）。
        if (origin == ConfigOrigin.FILE || origin == ConfigOrigin.SYSTEM_PROPERTY || origin == ConfigOrigin.RUNTIME_OVERRIDE) {
            throw new IllegalArgumentException("Unsupported security.mode: " + raw + " (allowed: off|hmac)");
        }
        return SecurityConfig.Mode.OFF;
    }

    private static String normalizeNonBlank(String v, String defaultValue) {
        if (v == null) {
            return defaultValue;
        }
        String s = v.trim();
        if (s.isEmpty()) {
            return defaultValue;
        }
        return s;
    }
}

