package com.javasleuth.command.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

public class BinaryFrameCodec {
    private BinaryFrameCodec() {}

    // Header layout:
    // [int magic][byte version][byte type][byte flags][byte reserved][int length][payload...]
    private static final int HEADER_BYTES = 12;

    public static void writeFrame(DataOutputStream out, BinaryFrame frame, int maxPayloadBytes) throws IOException {
        if (out == null || frame == null) {
            return;
        }

        byte[] payload = frame.getPayload();
        if (payload == null) {
            payload = new byte[0];
        }

        int max = normalizeMax(maxPayloadBytes);
        if (max > 0 && payload.length > max && (frame.getType() == BinaryFrame.Type.DATA || frame.getType() == BinaryFrame.Type.ERR)) {
            int offset = 0;
            while (offset < payload.length) {
                int end = Math.min(payload.length, offset + max);
                end = adjustUtf8ChunkEnd(payload, offset, end);
                if (end <= offset) {
                    end = Math.min(payload.length, offset + max);
                }
                writeSingle(out, frame.getType(), frame.getFlags(), payload, offset, end - offset);
                offset = end;
            }
            return;
        }

        writeSingle(out, frame.getType(), frame.getFlags(), payload, 0, payload.length);
    }

    public static BinaryFrame readFrame(DataInputStream in, int maxPayloadBytes) throws IOException {
        if (in == null) {
            return null;
        }

        try {
            int magic = in.readInt();
            if (magic != BinaryFrame.MAGIC) {
                throw new IOException("Invalid binary frame magic");
            }

            byte version = in.readByte();
            if (version != BinaryFrame.VERSION) {
                throw new IOException("Unsupported binary frame version: " + version);
            }

            int typeCode = in.readUnsignedByte();
            byte flags = in.readByte();
            in.readByte(); // reserved
            int length = in.readInt();
            if (length < 0) {
                throw new IOException("Invalid binary frame length: " + length);
            }

            int max = normalizeMax(maxPayloadBytes);
            if (max > 0 && length > max) {
                throw new IOException("Binary frame payload too large: " + length);
            }

            byte[] payload = new byte[length];
            in.readFully(payload);

            BinaryFrame.Type type = BinaryFrame.Type.fromCode(typeCode);
            if (type == null) {
                throw new IOException("Unknown binary frame type: " + typeCode);
            }

            return new BinaryFrame(type, flags, payload);
        } catch (EOFException eof) {
            return null;
        }
    }

    private static void writeSingle(DataOutputStream out, BinaryFrame.Type type, byte flags, byte[] payload, int offset, int len)
        throws IOException {
        out.writeInt(BinaryFrame.MAGIC);
        out.writeByte(BinaryFrame.VERSION);
        out.writeByte(type.getCode());
        out.writeByte(flags);
        out.writeByte(0); // reserved
        out.writeInt(len);
        if (len > 0) {
            out.write(payload, offset, len);
        }
        out.flush();
    }

    private static int normalizeMax(int max) {
        if (max <= 0) {
            return 0;
        }
        // Prevent pathological small max values that can break UTF-8 chunking.
        return Math.max(max, 16);
    }

    /**
     * Best-effort UTF-8 chunk boundary alignment to avoid splitting multibyte sequences.
     * If payload is not UTF-8, this still keeps chunk size within [offset,end].
     */
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

            // We cut in the middle of a sequence; drop the partial char.
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

