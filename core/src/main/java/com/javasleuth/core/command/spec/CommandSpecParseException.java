package com.javasleuth.core.command.spec;

public class CommandSpecParseException extends RuntimeException {
    private final String code;

    public CommandSpecParseException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
