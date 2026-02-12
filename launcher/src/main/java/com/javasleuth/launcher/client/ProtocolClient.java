package com.javasleuth.launcher.client;

import com.javasleuth.command.protocol.BinaryFrame;
import com.javasleuth.command.protocol.BinaryFrameCodec;
import com.javasleuth.command.protocol.Frame;
import com.javasleuth.command.protocol.FrameCodec;
import com.javasleuth.command.protocol.Utf8LineCodec;
import com.javasleuth.security.RequestSecurityManager;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 客户端协议会话（握手 + 协商 + framed/binary 收发）。
 *
 * <p>该类不依赖 JLine，不处理 JVM 选择/Attach，仅处理网络协议与命令执行。</p>
 */
public final class ProtocolClient implements AutoCloseable {
    private static final SecureRandom NONCE_RANDOM = new SecureRandom();

    private final Socket socket;
    private final BufferedInputStream in;
    private final BufferedOutputStream out;
    private final String welcomeMessage;

    private final boolean binary;
    private final boolean framed;
    private final boolean streamingEnabled;
    private final int maxPayloadBytes;
    private final int maxLineBytes;
    private final String connId;

    private final DataInputStream binaryIn;
    private final DataOutputStream binaryOut;

    private final RequestSecurityManager securityManager;

    private ProtocolClient(
        Socket socket,
        BufferedInputStream in,
        BufferedOutputStream out,
        String welcomeMessage,
        boolean binary,
        boolean framed,
        boolean streamingEnabled,
        int maxPayloadBytes,
        int maxLineBytes,
        String connId,
        DataInputStream binaryIn,
        DataOutputStream binaryOut
    ) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.welcomeMessage = welcomeMessage;
        this.binary = binary;
        this.framed = framed;
        this.streamingEnabled = streamingEnabled;
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxLineBytes = maxLineBytes;
        this.connId = connId;
        this.binaryIn = binaryIn;
        this.binaryOut = binaryOut;
        this.securityManager = RequestSecurityManager.getInstance();
    }

    public static ProtocolClient connect(
        String host,
        int port,
        String preferredProtocol,
        boolean streamingEnabledHint,
        int maxPayloadBytesHint,
        int maxLineBytesHint
    ) throws IOException {
        Socket socket = new Socket(host, port);
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());

        int maxPayloadBytes = maxPayloadBytesHint > 0 ? maxPayloadBytesHint : 1024 * 1024;
        int maxLineBytes = maxLineBytesHint > 0 ? maxLineBytesHint : Math.max(8192, maxPayloadBytes * 2);

        String welcome = Utf8LineCodec.readLine(in, maxLineBytes);

        String proposedConnId = generateNonce();
        Utf8LineCodec.writeLine(out, HandshakeClient.buildHelloLine(preferredProtocol, proposedConnId), true);

        String configLine = Utf8LineCodec.readLine(in, maxLineBytes);
        if (configLine == null || !configLine.startsWith("CONFIG")) {
            closeQuietly(socket);
            throw new IOException("Handshake failed: expected CONFIG but got: " + configLine);
        }

        HandshakeConfig hc = HandshakeClient.parseConfigLine(configLine);
        if (hc == null) {
            closeQuietly(socket);
            throw new IOException("Handshake failed: invalid CONFIG line: " + configLine);
        }

        String negotiated = hc.getProtocol();
        if (negotiated == null || negotiated.trim().isEmpty()) {
            closeQuietly(socket);
            throw new IOException("Handshake failed: CONFIG missing protocol");
        }
        String protocol = negotiated.trim().toLowerCase();
        boolean binary = "binary".equals(protocol);
        boolean framed = "framed".equals(protocol);
        if (!binary && !framed) {
            closeQuietly(socket);
            throw new IOException("Handshake failed: unsupported negotiated protocol: " + protocol);
        }

        boolean streamingEnabled = streamingEnabledHint;
        if (hc.isStreamingEnabled()) {
            streamingEnabled = true;
        }

        if (hc.getMaxPayloadBytes() != null && hc.getMaxPayloadBytes() > 0) {
            maxPayloadBytes = hc.getMaxPayloadBytes();
            maxLineBytes = Math.max(maxLineBytes, Math.max(8192, maxPayloadBytes * 2));
        }

        String connId = hc.getConnId();
        if (connId == null || connId.trim().isEmpty()) {
            connId = proposedConnId;
        }

        DataInputStream binaryIn = null;
        DataOutputStream binaryOut = null;
        if (binary) {
            Utf8LineCodec.writeLine(out, "UPGRADE BINARY", true);
            String ok = Utf8LineCodec.readLine(in, maxLineBytes);
            if (ok == null || !"OK".equalsIgnoreCase(ok.trim())) {
                closeQuietly(socket);
                throw new IOException("Binary upgrade failed: expected OK but got: " + ok);
            }
            binaryIn = new DataInputStream(in);
            binaryOut = new DataOutputStream(out);
        }

        return new ProtocolClient(
            socket,
            in,
            out,
            welcome,
            binary,
            framed,
            streamingEnabled,
            maxPayloadBytes,
            maxLineBytes,
            connId,
            binaryIn,
            binaryOut
        );
    }

    public String getWelcomeMessage() {
        return welcomeMessage;
    }

    public boolean isBinary() {
        return binary;
    }

    public boolean isFramed() {
        return framed;
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public int getMaxLineBytes() {
        return maxLineBytes;
    }

    public String getConnId() {
        return connId;
    }

    public CommandResult execute(String command, boolean stream, ProtocolOutput output) {
        if (command == null) {
            return CommandResult.error("Command is null");
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return CommandResult.ok(false);
        }

        boolean shouldStream = (binary || framed) && streamingEnabled && stream;
        String signed;
        try {
            signed = securityManager.signCommand(trimmed, System.currentTimeMillis(), generateNonce(), connId);
        } catch (Exception e) {
            return CommandResult.error("Failed to sign command: " + e.getMessage());
        }

        boolean hadErr = false;
        try {
            if (binary && binaryIn != null && binaryOut != null) {
                BinaryFrameCodec.writeFrame(binaryOut, BinaryFrame.request(signed, shouldStream), maxPayloadBytes);
                while (true) {
                    BinaryFrame frame = BinaryFrameCodec.readFrame(binaryIn, maxPayloadBytes);
                    if (frame == null) {
                        break;
                    }
                    if (frame.getType() == BinaryFrame.Type.DATA) {
                        if (output != null) {
                            output.onStdoutChunk(frame.getPayloadUtf8());
                        }
                    } else if (frame.getType() == BinaryFrame.Type.ERR) {
                        hadErr = true;
                        if (output != null) {
                            output.onStderrChunk(frame.getPayloadUtf8());
                        }
                    } else if (frame.getType() == BinaryFrame.Type.END) {
                        break;
                    }
                }
                return CommandResult.ok(hadErr);
            }

            String outbound = shouldStream ? "STREAM " + signed : "CMD " + signed;
            Utf8LineCodec.writeLine(out, outbound, true);
            while (true) {
                Frame frame = FrameCodec.readFrame(in, maxLineBytes);
                if (frame == null) {
                    break;
                }
                if (frame.getType() == Frame.Type.DATA) {
                    if (output != null) {
                        output.onStdoutLine(frame.getPayload());
                    }
                } else if (frame.getType() == Frame.Type.ERR) {
                    hadErr = true;
                    if (output != null) {
                        output.onStderrLine(frame.getPayload());
                    }
                } else if (frame.getType() == Frame.Type.END) {
                    break;
                }
            }
            return CommandResult.ok(hadErr);
        } catch (IOException e) {
            return CommandResult.error("IO error: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignore) {
            // ignore
        }
    }

    private static String generateNonce() {
        byte[] bytes = new byte[12];
        NONCE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

