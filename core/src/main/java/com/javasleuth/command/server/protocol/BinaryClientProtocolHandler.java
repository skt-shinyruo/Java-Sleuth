package com.javasleuth.command.server.protocol;

import com.javasleuth.command.protocol.BinaryFrame;
import com.javasleuth.command.protocol.BinaryFrameCodec;
import com.javasleuth.command.session.ClientSession;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.monitoring.MetricsCollector;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BinaryClientProtocolHandler {
    private static final String AUTH_REQUIRED_MESSAGE = "Authentication required. Use: auth <user> <password>";

    private final AtomicBoolean running;
    private final MetricsCollector metricsCollector;
    private final ProductionConfig config;
    private final CommandRequestExecutor executor;

    public BinaryClientProtocolHandler(
        AtomicBoolean running,
        MetricsCollector metricsCollector,
        ProductionConfig config,
        CommandRequestExecutor executor
    ) {
        this.running = running;
        this.metricsCollector = metricsCollector;
        this.config = config;
        this.executor = executor;
    }

    public void handle(
        DataInputStream in,
        DataOutputStream out,
        String clientId,
        String clientInfo,
        String baseSessionId,
        String connId,
        ClientSession clientSession
    ) throws IOException {
        int maxPayloadBytes = config.getFrameMaxPayload();
        CommandReplyChannel reply = new BinaryReplyChannel(out, maxPayloadBytes);

        while (running.get()) {
            BinaryFrame frame;
            try {
                frame = BinaryFrameCodec.readFrame(in, maxPayloadBytes);
            } catch (IOException e) {
                metricsCollector.recordError("protocol_binary_decode");
                throw e;
            }

            if (frame == null) {
                break;
            }

            if (frame.getType() == BinaryFrame.Type.PING) {
                BinaryFrameCodec.writeFrame(out, BinaryFrame.pong(), maxPayloadBytes);
                continue;
            }

            if (frame.getType() != BinaryFrame.Type.REQUEST) {
                continue;
            }

            boolean shouldClose = executor.execute(
                clientId,
                clientInfo,
                baseSessionId,
                connId,
                clientSession,
                true,
                frame.isStream(),
                frame.getPayloadUtf8(),
                reply,
                AUTH_REQUIRED_MESSAGE,
                "protocol_binary_write",
                "Client connection closed during binary send",
                "Client connection closed during binary close",
                "Client connection closed during binary error"
            );
            if (shouldClose) {
                break;
            }
        }
    }
}

