package com.javasleuth.core.command;

import com.javasleuth.core.command.impl.ConfigCommand;
import com.javasleuth.core.command.impl.HealthCommand;
import com.javasleuth.core.command.impl.MetricsCommand;
import com.javasleuth.core.command.impl.QuitCommand;
import com.javasleuth.core.command.impl.StopCommand;
import com.javasleuth.core.command.impl.VersionCommand;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class OperationsCommandProvider {
    Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext context) {
        List<CommandDescriptor> descriptors = new ArrayList<>();
        BuiltinCommandMetas.add(
            descriptors,
            "health",
            new HealthCommand(context.requireMetricsCollector(), context.requirePerformanceOptimizer()),
            CommandMeta.viewer(false, false)
        );
        BuiltinCommandMetas.add(
            descriptors,
            "metrics",
            new MetricsCommand(context.requireMetricsCollector()),
            CommandMeta.viewer(false, false)
        );
        ConfigCommand configCommand = new ConfigCommand(context.requireConfig());
        descriptors.add(CommandDescriptor.ofSpec(configCommand.getSpec(), configCommand));
        BuiltinCommandMetas.add(descriptors, "version", new VersionCommand(), CommandMeta.viewer(true, false));
        BuiltinCommandMetas.add(descriptors, "quit", new QuitCommand(), CommandMeta.viewer(false, false));
        BuiltinCommandMetas.add(
            descriptors,
            "stop",
            new StopCommand(context.getShutdownHook()),
            CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(1)
        );
        return descriptors;
    }
}
