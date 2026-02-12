package com.javasleuth.command.protocol;

import java.nio.charset.StandardCharsets;

public final class BinaryFrame {
    public enum Type {
        CMD(1),
        STREAM(2),
        DATA(3),
        ERR(4),
        END(5);

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
    private final int flags;
    private final byte[] payload;

    private BinaryFrame(Type type, int flags, byte[] payload) {
        this.type = type;
        this.flags = flags;
        this.payload = payload != null ? payload : new byte[0];
    }

    public static BinaryFrame of(Type type, int flags, byte[] payload) {
        return new BinaryFrame(type, flags, payload);
    }

    public static BinaryFrame request(String payloadUtf8, boolean stream) {
        Type t = stream ? Type.STREAM : Type.CMD;
        return new BinaryFrame(t, 0, utf8(payloadUtf8));
    }

    public static BinaryFrame data(String payloadUtf8) {
        return new BinaryFrame(Type.DATA, 0, utf8(payloadUtf8));
    }

    public static BinaryFrame err(String payloadUtf8) {
        return new BinaryFrame(Type.ERR, 0, utf8(payloadUtf8));
    }

    public static BinaryFrame end() {
        return new BinaryFrame(Type.END, 0, new byte[0]);
    }

    private static byte[] utf8(String s) {
        return (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
    }

    public Type getType() {
        return type;
    }

    public int getFlags() {
        return flags;
    }

    public byte[] getPayload() {
        return payload;
    }

    public String getPayloadUtf8() {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
