package com.javasleuth.core.command;

import com.javasleuth.foundation.security.CommandMeta;

/**
 * Single source of truth for command registration.
 *
 * <p>Combines the executable command with its registration metadata so providers cannot accidentally
 * publish one without the other.</p>
 */
public final class CommandDescriptor {
    private final String name;
    private final Command command;
    private final CommandMeta meta;

    private CommandDescriptor(String name, Command command, CommandMeta meta) {
        this.name = name;
        this.command = command;
        this.meta = meta;
    }

    public static CommandDescriptor of(String name, Command command, CommandMeta meta) {
        return new CommandDescriptor(name, command, meta);
    }

    public String getName() {
        return name;
    }

    public Command getCommand() {
        return command;
    }

    public CommandMeta getMeta() {
        return meta;
    }
}
