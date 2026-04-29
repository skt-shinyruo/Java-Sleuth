package com.javasleuth.core.command;

import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.core.command.spec.CommandSpec;

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
    private final CommandSpec spec;

    private CommandDescriptor(String name, Command command, CommandMeta meta) {
        this(name, command, meta, null);
    }

    private CommandDescriptor(String name, Command command, CommandMeta meta, CommandSpec spec) {
        this.name = name;
        this.command = command;
        this.meta = meta;
        this.spec = spec;
    }

    public static CommandDescriptor of(String name, Command command, CommandMeta meta) {
        return new CommandDescriptor(name, command, meta);
    }

    public static CommandDescriptor ofSpec(CommandSpec spec, Command command) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        return new CommandDescriptor(spec.getName(), command, spec.getMeta(), spec);
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

    public CommandSpec getSpec() {
        return spec;
    }
}
