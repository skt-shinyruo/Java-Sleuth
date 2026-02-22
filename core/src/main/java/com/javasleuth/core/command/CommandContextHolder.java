package com.javasleuth.core.command;

public class CommandContextHolder {
    private static final ThreadLocal<CommandContext> CONTEXT = new ThreadLocal<>();

    public static void set(CommandContext context) {
        CONTEXT.set(context);
    }

    public static CommandContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
