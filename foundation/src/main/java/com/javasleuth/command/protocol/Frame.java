package com.javasleuth.command.protocol;

public class Frame {
    public enum Type {
        DATA, END, ERR
    }

    private final Type type;
    private final String payload;

    private Frame(Type type, String payload) {
        this.type = type;
        this.payload = payload;
    }

    public static Frame data(String payload) {
        return new Frame(Type.DATA, payload);
    }

    public static Frame err(String payload) {
        return new Frame(Type.ERR, payload);
    }

    public static Frame end() {
        return new Frame(Type.END, null);
    }

    public Type getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }
}
