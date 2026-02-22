package com.javasleuth.core.command.server.protocol;

import com.javasleuth.foundation.command.protocol.BinaryFrame;
import com.javasleuth.foundation.command.protocol.BinaryFrameCodec;
import com.javasleuth.core.command.session.ClientDisconnectedException;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.config.model.ProtocolConfig;
import com.javasleuth.foundation.config.model.SecurityConfig;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BinaryClientProtocolHandler {
    private static final String AUTH_REQUIRED_MESSAGE =
        "Authentication required. " +
            "Use: auth <user> <password> (requires security.auth.password.enabled=true), " +
            "or set security.anonymous.viewer=true for viewer access.";

    private final AtomicBoolean running;
    private final MetricsCollector metricsCollector;
    private final CommandRequestExecutor executor;

    public BinaryClientProtocolHandler(
        AtomicBoolean running,
        MetricsCollector metricsCollector,
        CommandRequestExecutor executor
    ) {
        this.running = running;
        this.metricsCollector = metricsCollector;
        this.executor = executor;
    }

    public void handle(
        DataInputStream in,
        DataOutputStream out,
        int maxPayloadBytes,
        String clientId,
        String clientInfo,
        String baseSessionId,
        String connId,
        ClientSession clientSession,
        ProtocolConfig protocolConfig,
        SecurityConfig securityConfig
    ) throws IOException {
        CommandReplyChannel reply = new BinaryReplyChannel(out, maxPayloadBytes);

        while (running.get()) {
            BinaryFrame frame = BinaryFrameCodec.readFrame(in, maxPayloadBytes);
            if (frame == null) {
                break;
            }

            final boolean streamRequested;
            final String raw;
            if (frame.getType() == BinaryFrame.Type.CMD) {
                streamRequested = false;
                raw = frame.getPayloadUtf8();
            } else if (frame.getType() == BinaryFrame.Type.STREAM) {
                streamRequested = true;
                raw = frame.getPayloadUtf8();
            } else {
                reply.sendError("Protocol error: expected CMD or STREAM frame.");
                metricsCollector.recordError("protocol_binary_invalid_type");
                break;
            }

            try {
                boolean shouldClose = executor.execute(
                    clientId,
                    clientInfo,
                    baseSessionId,
                    connId,
                    clientSession,
                    protocolConfig,
                    securityConfig,
                    false,
                    streamRequested,
                    raw,
                    reply,
                    AUTH_REQUIRED_MESSAGE,
                    "protocol_binary_write",
                    "Client connection closed during send",
                    "Client connection closed during close",
                    "Client connection closed during error"
                );
                if (shouldClose) {
                    break;
                }
            } catch (ClientDisconnectedException disconnected) {
                break;
            }
        }
    }

    private static final class BinaryReplyChannel implements CommandReplyChannel {
        private final DataOutputStream out;
        private final int maxPayloadBytes;

        private BinaryReplyChannel(DataOutputStream out, int maxPayloadBytes) {
            this.out = out;
            this.maxPayloadBytes = maxPayloadBytes;
        }

        @Override
        public void sendData(String data) throws IOException {
            writeUtf8(BinaryFrame.Type.DATA, data);
        }

        @Override
        public void sendError(String message) throws IOException {
            writeUtf8(BinaryFrame.Type.ERR, message != null ? message : "Unknown error");
            BinaryFrameCodec.writeFrame(out, BinaryFrame.end(), maxPayloadBytes);
        }

        @Override
        public void end() throws IOException {
            BinaryFrameCodec.writeFrame(out, BinaryFrame.end(), maxPayloadBytes);
        }

        private void writeUtf8(BinaryFrame.Type type, String payload) throws IOException {
            byte[] bytes = (payload != null ? payload : "").getBytes(StandardCharsets.UTF_8);
            int max = normalizeMax(maxPayloadBytes);

            if (bytes.length == 0) {
                BinaryFrameCodec.writeFrame(out, BinaryFrame.of(type, 0, new byte[0]), maxPayloadBytes);
                return;
            }

            int offset = 0;
            while (offset < bytes.length) {
                int end = Math.min(bytes.length, offset + max);
                end = adjustUtf8ChunkEnd(bytes, offset, end);
                if (end <= offset) {
                    end = Math.min(bytes.length, offset + max);
                }

                int len = end - offset;
                byte[] chunk = new byte[len];
                System.arraycopy(bytes, offset, chunk, 0, len);
                BinaryFrameCodec.writeFrame(out, BinaryFrame.of(type, 0, chunk), maxPayloadBytes);
                offset = end;
            }
        }

        private static int normalizeMax(int max) {
            if (max <= 0) {
                return Integer.MAX_VALUE;
            }
            return Math.max(max, 16);
        }

        private static int adjustUtf8ChunkEnd(byte[] bytes, int offset, int end) {
            if (end - offset < 1) {
                return end;
            }
            if (end >= bytes.length) {
                return end;
            }

            int candidate = end;
            int attempts = 0;
            while (candidate > offset && attempts < 8) {
                attempts++;

                int cont = 0;
                int idx = candidate - 1;
                while (idx - cont >= offset && isContinuation(bytes[idx - cont])) {
                    cont++;
                }
                int leadIndex = idx - cont;
                if (leadIndex < offset) {
                    return candidate;
                }

                int expected = expectedUtf8Length(bytes[leadIndex]);
                int actual = cont + 1;
                if (expected == actual) {
                    return candidate;
                }

                candidate = leadIndex;
            }

            return candidate;
        }

        private static boolean isContinuation(byte b) {
            return (b & 0xC0) == 0x80;
        }

        private static int expectedUtf8Length(byte lead) {
            int u = lead & 0xFF;
            if ((u & 0x80) == 0) {
                return 1;
            }
            if ((u & 0xE0) == 0xC0) {
                return 2;
            }
            if ((u & 0xF0) == 0xE0) {
                return 3;
            }
            if ((u & 0xF8) == 0xF0) {
                return 4;
            }
            return 1;
        }
    }
}
