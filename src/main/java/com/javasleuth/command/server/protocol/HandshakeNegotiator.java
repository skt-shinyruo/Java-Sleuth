package com.javasleuth.command.server.protocol;

import com.javasleuth.command.protocol.Utf8LineCodec;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.monitoring.MetricsCollector;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class HandshakeNegotiator {
    private final ProductionConfig config;
    private final MetricsCollector metricsCollector;

    public HandshakeNegotiator(ProductionConfig config, MetricsCollector metricsCollector) {
        this.config = config;
        this.metricsCollector = metricsCollector;
    }

    public NegotiationResult handleHello(String line, OutputStream out) throws IOException {
        metricsCollector.recordHandshake();
        Map<String, String> kv = parseHandshakeKv(line);
        Set<String> clientProtocols = parseProtocols(kv.get("protocols"));
        String requested = kv.get("protocol");
        if (requested != null && !requested.trim().isEmpty()) {
            clientProtocols.add(requested.trim().toLowerCase());
        }
        if (clientProtocols.isEmpty()) {
            clientProtocols.add("legacy");
        }

        String preferred = config.getProtocolMode();
        if (preferred == null) {
            preferred = "legacy";
        }
        preferred = preferred.toLowerCase();

        String selected;
        if ("binary".equals(preferred) && clientProtocols.contains("binary")) {
            selected = "binary";
        } else if ("framed".equals(preferred) && clientProtocols.contains("framed")) {
            selected = "framed";
        } else if ("binary".equals(preferred) && clientProtocols.contains("framed")) {
            selected = "framed";
        } else {
            selected = "legacy";
        }

        String connId = kv.get("connid");
        Utf8LineCodec.writeLine(out, buildConfigLine(selected, connId), true);
        return new NegotiationResult(selected, connId);
    }

    private String buildConfigLine(String protocol, String connId) {
        return "CONFIG v=1" +
            " protocol=" + protocol +
            " streaming=" + config.isStreamingEnabled() +
            " maxPayload=" + config.getFrameMaxPayload() +
            " port=" + config.getServerPort() +
            " bind=" + config.getServerBindAddress() +
            " securityMode=" + config.getSecurityMode() +
            " authorization=" + config.isAuthorizationEnabled() +
            (connId != null ? " connId=" + connId : "");
    }

    static Map<String, String> parseHandshakeKv(String line) {
        Map<String, String> kv = new HashMap<>();
        if (line == null) {
            return kv;
        }
        String[] tokens = line.trim().split("\\s+");
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            int idx = token.indexOf('=');
            if (idx <= 0 || idx >= token.length() - 1) {
                continue;
            }
            String k = token.substring(0, idx).trim().toLowerCase();
            String v = token.substring(idx + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) {
                kv.put(k, v);
            }
        }
        return kv;
    }

    static Set<String> parseProtocols(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null || csv.trim().isEmpty()) {
            return out;
        }
        String[] parts = csv.split(",");
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String v = p.trim().toLowerCase();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    public static final class NegotiationResult {
        private final String protocol;
        private final String connId;

        NegotiationResult(String protocol, String connId) {
            this.protocol = protocol;
            this.connId = connId;
        }

        public String getProtocol() {
            return protocol;
        }

        public String getConnId() {
            return connId;
        }
    }
}

