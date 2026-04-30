package com.javasleuth.core.command;

import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.List;

final class BuiltinCommandMetas {
    private BuiltinCommandMetas() {
    }

    static void add(List<CommandDescriptor> descriptors, String name, Command command, CommandMeta meta) {
        descriptors.add(CommandDescriptor.of(name, command, meta));
    }

    static CommandMeta writesDisk(CommandMeta meta) {
        return meta.withCapability(CommandCapability.WRITES_DISK);
    }
}
