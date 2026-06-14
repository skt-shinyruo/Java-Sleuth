package com.javasleuth.launcher.client;

import com.javasleuth.foundation.command.protocol.BinaryFrame;
import com.javasleuth.foundation.command.protocol.BinaryFrameCodec;
import com.javasleuth.foundation.command.protocol.Utf8LineCodec;
import com.javasleuth.foundation.security.CommandSigner;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 客户端协议会话（握手 + 协商 + binary frame 收发）。
 *
 * <p>该类不依赖 JLine，不处理 JVM 选择/Attach，仅处理网络协议与命令执行。</p>
 */
public final class ProtocolClient implements AutoCloseable {
    private static final SecureRandom NONCE_RANDOM = new SecureRandom();
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_HANDSHAKE_READ_TIMEOUT_MS = 15000;
    private static final int DEFAULT_OVERALL_TIMEOUT_MS = 15000;
    private static final int DEFAULT_INITIAL_BACKOFF_MS = 100;

    private final Socket socket;
    private final BufferedInputStream in;
    private final BufferedOutputStream out;
    private final String welcomeMessage;

    private final boolean binary;
    private final boolean streamingEnabled;
    private final int maxPayloadBytes;
    private final int maxLineBytes;
    private final String connId;

    private final DataInputStream binaryIn;
    private final DataOutputStream binaryOut;

    private final CommandSigner signer;

    private ProtocolClient(
        Socket socket,
        BufferedInputStream in,
        BufferedOutputStream out,
        String welcomeMessage,
        boolean binary,
        boolean streamingEnabled,
        int maxPayloadBytes,
        int maxLineBytes,
        String connId,
        DataInputStream binaryIn,
        DataOutputStream binaryOut,
        CommandSigner signer
    ) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.welcomeMessage = welcomeMessage;
        this.binary = binary;
        this.streamingEnabled = streamingEnabled;
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxLineBytes = maxLineBytes;
        this.connId = connId;
        this.binaryIn = binaryIn;
        this.binaryOut = binaryOut;
        this.signer = signer != null ? signer : CommandSigner.noop();
    }

    public static ProtocolClient connect(
        String host,
        int port,
        String preferredProtocol,
        boolean streamingEnabledHint,
        int maxPayloadBytesHint,
        int maxLineBytesHint
    ) throws IOException {
        return connect(
            host,
            port,
            preferredProtocol,
            streamingEnabledHint,
            maxPayloadBytesHint,
            maxLineBytesHint,
            CommandSigner.noop()
        );
    }

    public static ProtocolClient connect(
        String host,
        int port,
        String preferredProtocol,
        boolean streamingEnabledHint,
        int maxPayloadBytesHint,
        int maxLineBytesHint,
        CommandSigner signer
    ) throws IOException {
        return connect(
            host,
            port,
            preferredProtocol,
            streamingEnabledHint,
            maxPayloadBytesHint,
            maxLineBytesHint,
            signer,
            DEFAULT_CONNECT_TIMEOUT_MS,
            DEFAULT_HANDSHAKE_READ_TIMEOUT_MS
        );
    }

    public static ProtocolClient connect(
        String host,
        int port,
        String preferredProtocol,
        boolean streamingEnabledHint,
        int maxPayloadBytesHint,
        int maxLineBytesHint,
        CommandSigner signer,
        int connectTimeoutMs,
        int handshakeReadTimeoutMs
    ) throws IOException {
        Socket socket = new Socket();
        int ct = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
        int rt = handshakeReadTimeoutMs > 0 ? handshakeReadTimeoutMs : DEFAULT_HANDSHAKE_READ_TIMEOUT_MS;

        // Like Arthas: make connect/read bounded to avoid "hang forever" under network issues.
        boolean connected = false;
        try {
            socket.connect(new InetSocketAddress(host, port), ct);
            ProtocolClient client = handshakeOnSocket(
                socket,
                preferredProtocol,
                streamingEnabledHint,
                maxPayloadBytesHint,
                maxLineBytesHint,
                signer,
                rt
            );
            connected = true;
            return client;
        } finally {
            if (!connected) {
                closeQuietly(socket);
            }
        }
    }

    private static ProtocolClient handshakeOnSocket(
        Socket socket,
        String preferredProtocol,
        boolean streamingEnabledHint,
        int maxPayloadBytesHint,
        int maxLineBytesHint,
        CommandSigner signer,
        int handshakeReadTimeoutMs
    ) throws IOException {
        int rt = handshakeReadTimeoutMs > 0 ? handshakeReadTimeoutMs : DEFAULT_HANDSHAKE_READ_TIMEOUT_MS;
        socket.setSoTimeout(rt);
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
        if (!binary) {
            closeQuietly(socket);
            throw new IOException("Handshake failed: unsupported negotiated protocol: " + protocol + " (binary required)");
        }

        // 服务端 CONFIG streaming= 是最终协议能力的 SSOT：客户端不能用本地 hint 覆盖服务端的关闭状态。
        boolean streamingEnabled = hc.isStreamingEnabled();

        if (hc.getMaxPayloadBytes() != null && hc.getMaxPayloadBytes() > 0) {
            maxPayloadBytes = hc.getMaxPayloadBytes();
            maxLineBytes = Math.max(maxLineBytes, Math.max(8192, maxPayloadBytes * 2));
        }

        String connId = hc.getConnId();
        if (connId == null || connId.trim().isEmpty()) {
            connId = proposedConnId;
        }

        Utf8LineCodec.writeLine(out, "UPGRADE BINARY", true);
        String ok = Utf8LineCodec.readLine(in, maxLineBytes);
        if (ok == null || !"OK".equalsIgnoreCase(ok.trim())) {
            closeQuietly(socket);
            throw new IOException("Binary upgrade failed: expected OK but got: " + ok);
        }
        DataInputStream binaryIn = new DataInputStream(in);
        DataOutputStream binaryOut = new DataOutputStream(out);

        ProtocolClient client = new ProtocolClient(
            socket,
            in,
            out,
            welcome,
            binary,
            streamingEnabled,
            maxPayloadBytes,
            maxLineBytes,
            connId,
            binaryIn,
            binaryOut,
            signer != null ? signer : CommandSigner.noop()
        );
        // Handshake completed: don't apply read timeout to normal command execution (watch/trace may stream for long time).
        socket.setSoTimeout(0);
        return client;
    }

    public static ProtocolClient connectWithRetry(
        String host,
        int port,
        String preferredProtocol,
        boolean streamingEnabledHint,
        int maxPayloadBytesHint,
        int maxLineBytesHint
    ) throws IOException {
        return connectWithRetry(
            host,
            port,
            preferredProtocol,
            streamingEnabledHint,
            maxPayloadBytesHint,
            maxLineBytesHint,
            DEFAULT_OVERALL_TIMEOUT_MS,
            DEFAULT_CONNECT_TIMEOUT_MS,
            DEFAULT_HANDSHAKE_READ_TIMEOUT_MS
        );
    }

    public static ProtocolClient connectWithRetry(
        String host,
        int port,
        String preferredProtocol,
        boolean streamingEnabledHint,
        int maxPayloadBytesHint,
        int maxLineBytesHint,
        int overallTimeoutMs,
        int connectTimeoutMs,
        int handshakeReadTimeoutMs
    ) throws IOException {
        long start = System.currentTimeMillis();
        long deadline = start + Math.max(1000, overallTimeoutMs);

        IOException last = null;
        int attempts = 0;
        int backoffMs = DEFAULT_INITIAL_BACKOFF_MS;

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            try {
                long now = System.currentTimeMillis();
                long remaining = deadline - now;
                if (remaining <= 0) {
                    break;
                }

                int ct = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
                int rt = handshakeReadTimeoutMs > 0 ? handshakeReadTimeoutMs : DEFAULT_HANDSHAKE_READ_TIMEOUT_MS;

                // Cap per-attempt timeouts by remaining budget to make overall timeout meaningful.
                int attemptConnectTimeout = (int) Math.min(ct, Math.min(Integer.MAX_VALUE, remaining));

                Socket socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress(host, port), attemptConnectTimeout);

                    long afterConnect = System.currentTimeMillis();
                    long remainingAfterConnect = deadline - afterConnect;
                    if (remainingAfterConnect <= 0) {
                        closeQuietly(socket);
                        break;
                    }
                    int attemptHandshakeTimeout = (int) Math.min(rt, Math.min(Integer.MAX_VALUE, remainingAfterConnect));

                    return handshakeOnSocket(
                        socket,
                        preferredProtocol,
                        streamingEnabledHint,
                        maxPayloadBytesHint,
                        maxLineBytesHint,
                        CommandSigner.noop(),
                        attemptHandshakeTimeout
                    );
                } catch (IOException e) {
                    closeQuietly(socket);
                    throw e;
                }
            } catch (IOException e) {
                last = e;
                long now = System.currentTimeMillis();
                long remaining = deadline - now;
                if (remaining <= 0) {
                    break;
                }
                try {
                    Thread.sleep(Math.min(backoffMs, (int) Math.min(Integer.MAX_VALUE, remaining)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    IOException out = new IOException("Connect interrupted: host=" + host + " port=" + port);
                    out.addSuppressed(e);
                    throw out;
                }
                backoffMs = Math.min(1000, backoffMs * 2);
            }
        }

        long spent = System.currentTimeMillis() - start;
        IOException out = new IOException(
            "Failed to connect to agent within " + spent + "ms (timeout=" + overallTimeoutMs + "ms, attempts=" + attempts + "): host=" + host + " port=" + port +
                (last != null ? ", lastError=" + last.getMessage() : "")
        );
        if (last != null) {
            out.addSuppressed(last);
        }
        throw out;
    }

    public String getWelcomeMessage() {
        return welcomeMessage;
    }

    public boolean isBinary() {
        return binary;
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

        boolean shouldStream = streamingEnabled && stream;
        String signed;
        try {
            signed = signer.sign(trimmed, System.currentTimeMillis(), generateNonce(), connId);
        } catch (Exception e) {
            return CommandResult.error("Failed to sign command: " + e.getMessage());
        }

        boolean hadErr = false;
        try {
            if (!binary || binaryIn == null || binaryOut == null) {
                return CommandResult.error("Protocol error: binary data plane not available");
            }

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
        } catch (IOException e) {
            return CommandResult.error("IO error: " + e.getMessage());
        }
    }

    public CommandResult authenticate(String username, String password, ProtocolOutput output) {
        if (username == null || username.trim().isEmpty()) {
            return CommandResult.error("Auth username is empty");
        }
        if (password == null || password.trim().isEmpty()) {
            return CommandResult.error("Auth password is empty");
        }

        CapturingOutput capture = new CapturingOutput(output);
        CommandResult result = execute(
            "auth " + quoteCommandArg(username) + " " + quoteCommandArg(password),
            false,
            capture
        );
        if (result == null || !result.isOk()) {
            return result != null ? result : CommandResult.error("Authentication command failed");
        }
        if (capture.getStdout().toLowerCase(java.util.Locale.ROOT).contains("authenticated as")) {
            return CommandResult.ok(false);
        }
        String message = firstNonBlank(capture.getStderr(), capture.getStdout(), "authentication failed");
        return CommandResult.error(message);
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

    private static String quoteCommandArg(String value) {
        if (value == null) {
            return "\"\"";
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return "\"\"";
        }
        if (isBareCommandArg(v)) {
            return v;
        }
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean isBareCommandArg(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\\') {
                return false;
            }
        }
        return true;
    }

    private static String firstNonBlank(String a, String b, String fallback) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return fallback;
    }

    private static final class CapturingOutput implements ProtocolOutput {
        private final ProtocolOutput delegate;
        private final StringBuilder stdout = new StringBuilder();
        private final StringBuilder stderr = new StringBuilder();

        private CapturingOutput(ProtocolOutput delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onStdoutLine(String line) {
            stdout.append(line).append('\n');
            if (delegate != null) {
                delegate.onStdoutLine(line);
            }
        }

        @Override
        public void onStderrLine(String line) {
            stderr.append(line).append('\n');
            if (delegate != null) {
                delegate.onStderrLine(line);
            }
        }

        @Override
        public void onStdoutChunk(String chunk) {
            stdout.append(chunk);
            if (delegate != null) {
                delegate.onStdoutChunk(chunk);
            }
        }

        @Override
        public void onStderrChunk(String chunk) {
            stderr.append(chunk);
            if (delegate != null) {
                delegate.onStderrChunk(chunk);
            }
        }

        private String getStdout() {
            return stdout.toString();
        }

        private String getStderr() {
            return stderr.toString();
        }
    }
}
