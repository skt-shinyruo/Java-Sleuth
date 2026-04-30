package com.javasleuth.core.command;

import com.javasleuth.core.agent.runtime.BootstrapBridge;
import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.List;

final class BuiltinCommandMetas {
    private BuiltinCommandMetas() {
    }

    static void add(List<CommandDescriptor> descriptors, String name, Command command, CommandMeta meta) {
        descriptors.add(CommandDescriptor.of(name, command, meta));
    }

    static CommandMeta instrumentationStream() {
        return CommandMeta.operator(false, true)
            .requiresBootstrap(BootstrapBridge.SPY_API)
            .withCapability(CommandCapability.LONG_RUNNING);
    }

    static CommandMeta writesDisk(CommandMeta meta) {
        return meta.withCapability(CommandCapability.WRITES_DISK);
    }
}
