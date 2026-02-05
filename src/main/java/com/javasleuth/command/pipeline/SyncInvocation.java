package com.javasleuth.command.pipeline;

import com.javasleuth.command.Command;
import com.javasleuth.command.CommandContext;
import com.javasleuth.command.CommandMeta;
import com.javasleuth.command.CommandRegistry;

public final class SyncInvocation {
    private final CommandRegistry.Entry entry;
    private final Command command;
    private final CommandMeta meta;
    private final String commandName;
    private final String[] args;
    private final CommandContext context;
    private final long timeoutMs;

    public SyncInvocation(
        CommandRegistry.Entry entry,
        Command command,
        CommandMeta meta,
        String commandName,
        String[] args,
        CommandContext context,
        long timeoutMs
    ) {
        this.entry = entry;
        this.command = command;
        this.meta = meta;
        this.commandName = commandName;
        this.args = args;
        this.context = context;
        this.timeoutMs = timeoutMs;
    }

    public CommandRegistry.Entry getEntry() {
        return entry;
    }

    public Command getCommand() {
        return command;
    }

    public CommandMeta getMeta() {
        return meta;
    }

    public String getCommandName() {
        return commandName;
    }

    public String[] getArgs() {
        return args;
    }

    public CommandContext getContext() {
        return context;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }
}

