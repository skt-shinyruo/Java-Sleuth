package com.javasleuth.foundation.command.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

public final class BinaryFrameCodec {
    private static final int MAGIC = 0x534C4555; // "SLEU"
    private static final int VERSION = 1;

    private BinaryFrameCodec() {}

    public static void writeFrame(DataOutputStream out, BinaryFrame frame, int maxPayloadBytes) throws IOException {
        if (out == null || frame == null) {
            return;
        }

        BinaryFrame.Type type = frame.getType();
        int flags = frame.getFlags();
        byte[] payload = frame.getPayload();
        if (payload == null) {
            payload = new byte[0];
        }

        int max = normalizeMax(maxPayloadBytes);
        if ((type == BinaryFrame.Type.DATA || type == BinaryFrame.Type.ERR) && payload.length > max) {
            int offset = 0;
            while (offset < payload.length) {
                int end = Math.min(payload.length, offset + max);
                end = adjustUtf8ChunkEnd(payload, offset, end);
                if (end <= offset) {
                    end = Math.min(payload.length, offset + max);
                }

                int len = end - offset;
                byte[] chunk = new byte[len];
                System.arraycopy(payload, offset, chunk, 0, len);
                writeSingle(out, type, flags, chunk);
                offset = end;
            }
            return;
        }

        writeSingle(out, type, flags, payload);
    }

    public static BinaryFrame readFrame(DataInputStream in, int maxPayloadBytes) throws IOException {
        if (in == null) {
            return null;
        }

        final int magic;
        try {
            magic = in.readInt();
        } catch (EOFException eof) {
            return null;
        }

        if (magic != MAGIC) {
            throw new IOException("Invalid binary frame magic: 0x" + Integer.toHexString(magic));
        }

        int version = in.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException("Unsupported binary frame version: " + version);
        }

        int typeCode = in.readUnsignedByte();
        BinaryFrame.Type type = BinaryFrame.Type.fromCode(typeCode);
        if (type == null) {
            throw new IOException("Unsupported binary frame type: " + typeCode);
        }

        int flags = in.readUnsignedShort();
        int len = in.readInt();
        if (len < 0) {
            throw new IOException("Invalid binary frame length: " + len);
        }
        int max = normalizeMax(maxPayloadBytes);
        if (len > max) {
            throw new IOException("Binary frame payload too large: " + len + " (maxPayloadBytes=" + max + ")");
        }

        byte[] payload = new byte[len];
        if (len > 0) {
            in.readFully(payload);
        }

        return BinaryFrame.of(type, flags, payload);
    }

    private static int normalizeMax(int maxPayloadBytes) {
        if (maxPayloadBytes <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(maxPayloadBytes, 16);
    }

    private static void writeSingle(DataOutputStream out, BinaryFrame.Type type, int flags, byte[] payload) throws IOException {
        if (out == null) {
            return;
        }
        if (payload == null) {
            payload = new byte[0];
        }

        int len = (type == BinaryFrame.Type.END) ? 0 : payload.length;

        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(type.getCode());
        out.writeShort(flags & 0xFFFF);
        out.writeInt(len);
        if (len > 0) {
            out.write(payload, 0, len);
        }
        out.flush();
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
