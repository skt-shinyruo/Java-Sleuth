package com.javasleuth.command.pipeline;

import com.javasleuth.command.CommandContext;
import com.javasleuth.command.CommandRegistry;
import com.javasleuth.command.StreamCommand;
import com.javasleuth.command.StreamSink;
import com.javasleuth.security.CommandMeta;

public final class StreamInvocation {
    private final CommandRegistry.Entry entry;
    private final StreamCommand command;
    private final CommandMeta meta;
    private final String commandName;
    private final String[] args;
    private final CommandContext context;
    private final long timeoutMs;
    private final StreamSink sink;

    public StreamInvocation(
        CommandRegistry.Entry entry,
        StreamCommand command,
        CommandMeta meta,
        String commandName,
        String[] args,
        CommandContext context,
        long timeoutMs,
        StreamSink sink
    ) {
        this.entry = entry;
        this.command = command;
        this.meta = meta;
        this.commandName = commandName;
        this.args = args;
        this.context = context;
        this.timeoutMs = timeoutMs;
        this.sink = sink;
    }

    public CommandRegistry.Entry getEntry() {
        return entry;
    }

    public StreamCommand getCommand() {
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

    public StreamSink getSink() {
        return sink;
    }

    public StreamInvocation withSink(StreamSink newSink) {
        return new StreamInvocation(entry, command, meta, commandName, args, context, timeoutMs, newSink);
    }
}
