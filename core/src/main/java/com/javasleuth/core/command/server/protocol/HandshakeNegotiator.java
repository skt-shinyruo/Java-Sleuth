package com.javasleuth.core.command.server.protocol;

import com.javasleuth.foundation.command.protocol.KvLineCodec;
import com.javasleuth.foundation.command.protocol.Utf8LineCodec;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.core.monitoring.MetricsCollector;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class HandshakeNegotiator {
    private final SleuthConfig config;
    private final MetricsCollector metricsCollector;

    public HandshakeNegotiator(SleuthConfig config, MetricsCollector metricsCollector) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
        this.metricsCollector = metricsCollector;
    }

    public NegotiationResult handleHello(String line, OutputStream out) throws IOException {
        metricsCollector.recordHandshake();
        Map<String, String> kv = KvLineCodec.parseAfterVerb(line);
        String helloV = kv.get("v");
        if (helloV == null || !"1".equals(helloV.trim())) {
            throw new IOException("Unsupported HELLO version: " + helloV);
        }
        Set<String> clientProtocols = parseProtocols(kv.get("protocols"));
        String requested = kv.get("protocol");
        String protocolsRaw = kv.get("protocols");
        if (protocolsRaw == null || protocolsRaw.trim().isEmpty()) {
            throw new IOException("Handshake requires protocols");
        }
        if (requested == null || requested.trim().isEmpty()) {
            throw new IOException("Handshake requires protocol");
        }
        String requestedNorm = requested.trim().toLowerCase();
        if ("framed".equals(requestedNorm)) {
            throw new IOException("Unsupported handshake protocol: framed (legacy protocol removed; use protocol=binary)");
        }
        if (!"binary".equals(requestedNorm)) {
            throw new IOException("Unsupported handshake protocol: " + requested);
        }
        boolean requestedListed = false;
        for (String p : protocolsRaw.split(",")) {
            if (requestedNorm.equals(p.trim().toLowerCase())) {
                requestedListed = true;
                break;
            }
        }
        if (!requestedListed) {
            throw new IOException("Handshake protocol not listed in protocols: " + requested);
        }
        if (requested != null && !requested.trim().isEmpty()) {
            clientProtocols.add(requested.trim().toLowerCase());
        }
        if (clientProtocols.isEmpty()) {
            clientProtocols.add("framed");
        }
        String selected = "binary";

        String connId = kv.get("connid");
        if (connId == null || connId.trim().isEmpty()) {
            throw new IOException("Handshake requires connId");
        }
        Utf8LineCodec.writeLine(out, buildConfigLine(selected, connId), true);
        return new NegotiationResult(selected, connId);
    }

    private String buildConfigLine(String protocol, String connId) {
        return "CONFIG v=1" +
            " protocol=" + protocol +
            " streaming=" + config.protocol().isStreamingEnabled() +
            " maxPayload=" + config.protocol().getFrameMaxPayloadBytes() +
            " port=" + config.server().getPort() +
            " bind=" + config.server().getBindAddress() +
            " securityMode=" + config.security().getModeWireName() +
            " authorization=" + config.security().isAuthorizationEnabled() +
            (connId != null ? " connId=" + connId : "");
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
