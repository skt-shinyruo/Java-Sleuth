package com.javasleuth.foundation.command.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
public class FrameCodec {
    private FrameCodec() {}

    

    

    public static Frame readFrame(BufferedReader reader) throws IOException {
        String header = reader.readLine();
        if (header == null) {
            return null;
        }

        if ("END".equals(header)) {
            return Frame.end();
        }

        if (header.startsWith("DATA ") || header.startsWith("ERR ")) {
            Frame.Type type = header.startsWith("DATA ") ? Frame.Type.DATA : Frame.Type.ERR;
            String payload = reader.readLine();
            return type == Frame.Type.DATA ? Frame.data(payload) : Frame.err(payload);
        }

        return Frame.err(header);
    }

    /**
     * Stream-based framed protocol for avoiding mixed buffered wrappers on the same socket.
     */
    public static void writeFrame(OutputStream out, Frame frame, int maxPayloadSize) throws IOException {
        if (frame == null) {
            return;
        }

        switch (frame.getType()) {
            case END:
                Utf8LineCodec.writeLine(out, "END", true);
                return;
            case ERR:
            case DATA:
                writePayload(out, frame.getType(), frame.getPayload(), maxPayloadSize);
                return;
            default:
                break;
        }
    }

    public static void writePayload(OutputStream out, Frame.Type type, String payload, int maxPayloadSize) throws IOException {
        if (payload == null) {
            payload = "";
        }
        int max = normalizeMax(maxPayloadSize);

        // Split by newline first to avoid embedding '\n' inside a single payload line.
        // This keeps the wire format compatible with readLineBytes() framing.
        String normalized = payload.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 0) {
            Utf8LineCodec.writeLine(out, type.name() + " 0", false);
            Utf8LineCodec.writeLine(out, "", true);
            return;
        }

        for (String line : lines) {
            String v = line != null ? line : "";
            byte[] bytes = v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length == 0) {
                Utf8LineCodec.writeLine(out, type.name() + " 0", false);
                Utf8LineCodec.writeLine(out, "", true);
                continue;
            }

            int offset = 0;
            while (offset < bytes.length) {
                int end = Math.min(bytes.length, offset + max);
                end = adjustUtf8ChunkEnd(bytes, offset, end);
                if (end <= offset) {
                    end = Math.min(bytes.length, offset + max);
                }

                int len = end - offset;
                Utf8LineCodec.writeLine(out, type.name() + " " + len, false);
                Utf8LineCodec.writeBytesLine(out, bytes, offset, len, true);
                offset = end;
            }
        }
    }

    private static int normalizeMax(int max) {
        if (max <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(max, 16);
    }

    private static int parseLen(String header) {
        int idx = header.indexOf(' ');
        if (idx < 0 || idx >= header.length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(header.substring(idx + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
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

    public static Frame readFrame(InputStream in, int maxLineBytes) throws IOException {
        String header = Utf8LineCodec.readLine(in, maxLineBytes);
        if (header == null) {
            return null;
        }

        if ("END".equals(header)) {
            return Frame.end();
        }

        if (header.startsWith("DATA ") || header.startsWith("ERR ")) {
            Frame.Type type = header.startsWith("DATA ") ? Frame.Type.DATA : Frame.Type.ERR;
            int length = parseLen(header);

            byte[] payloadBytes = Utf8LineCodec.readLineBytes(in, maxLineBytes);
            if (payloadBytes == null) {
                payloadBytes = new byte[0];
            }

            // length is bytes; decode what we got to stay robust to mismatch.
            if (length >= 0 && payloadBytes.length > length) {
                // If sender sent extra bytes before newline, trim to declared length.
                byte[] trimmed = new byte[length];
                System.arraycopy(payloadBytes, 0, trimmed, 0, length);
                payloadBytes = trimmed;
            }
            String payload = new String(payloadBytes, java.nio.charset.StandardCharsets.UTF_8);
            return type == Frame.Type.DATA ? Frame.data(payload) : Frame.err(payload);
        }

        return Frame.err(header);
    }
}
