package com.javasleuth.core.command;

/**
 * Command registry and execution pipeline assembly result.
 */
final class CommandSubsystem {
    final CommandRegistry registry;
    final CommandPipeline pipeline;

    CommandSubsystem(CommandRegistry registry, CommandPipeline pipeline) {
        this.registry = registry;
        this.pipeline = pipeline;
    }
}
