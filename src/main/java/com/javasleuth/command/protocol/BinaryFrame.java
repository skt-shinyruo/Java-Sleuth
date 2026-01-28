package com.javasleuth.command.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class BinaryFrame {
    public static final int MAGIC = 0x4A534C45; // 'JSLE'
    public static final byte VERSION = 1;

    public static final byte FLAG_STREAM = 1;
    public static final byte FLAG_STDERR = 2;

    public enum Type {
        HELLO(1),
        CONFIG(2),
        REQUEST(3),
        DATA(4),
        ERR(5),
        END(6),
        PING(7),
        PONG(8);

        private final int code;

        Type(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static Type fromCode(int code) {
            for (Type t : values()) {
                if (t.code == code) {
                    return t;
                }
            }
            return null;
        }
    }

    private final Type type;
    private final byte flags;
    private final byte[] payload;

    public BinaryFrame(Type type, byte flags, byte[] payload) {
        this.type = type;
        this.flags = flags;
        this.payload = payload != null ? payload : new byte[0];
    }

    public Type getType() {
        return type;
    }

    public byte getFlags() {
        return flags;
    }

    public boolean isStream() {
        return (flags & FLAG_STREAM) != 0;
    }

    public boolean isStderr() {
        return (flags & FLAG_STDERR) != 0;
    }

    public byte[] getPayload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public String getPayloadUtf8() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    public static BinaryFrame hello(String payload) {
        return new BinaryFrame(Type.HELLO, (byte) 0, utf8Bytes(payload));
    }

    public static BinaryFrame config(String payload) {
        return new BinaryFrame(Type.CONFIG, (byte) 0, utf8Bytes(payload));
    }

    public static BinaryFrame request(String commandLine, boolean stream) {
        byte flags = 0;
        if (stream) {
            flags |= FLAG_STREAM;
        }
        return new BinaryFrame(Type.REQUEST, flags, utf8Bytes(commandLine));
    }

    public static BinaryFrame data(String payload) {
        return new BinaryFrame(Type.DATA, (byte) 0, utf8Bytes(payload));
    }

    public static BinaryFrame err(String payload) {
        return new BinaryFrame(Type.ERR, FLAG_STDERR, utf8Bytes(payload));
    }

    public static BinaryFrame end() {
        return new BinaryFrame(Type.END, (byte) 0, new byte[0]);
    }

    public static BinaryFrame ping() {
        return new BinaryFrame(Type.PING, (byte) 0, new byte[0]);
    }

    public static BinaryFrame pong() {
        return new BinaryFrame(Type.PONG, (byte) 0, new byte[0]);
    }

    private static byte[] utf8Bytes(String s) {
        if (s == null) {
            return new byte[0];
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }
}

