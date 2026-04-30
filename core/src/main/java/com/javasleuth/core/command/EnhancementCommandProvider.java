package com.javasleuth.core.command;

import com.javasleuth.core.command.impl.JobsCommand;
import com.javasleuth.core.command.impl.EnhanceCommand;
import com.javasleuth.core.command.impl.MonitorCommand;
import com.javasleuth.core.command.impl.ResetCommand;
import com.javasleuth.core.command.impl.StackCommand;
import com.javasleuth.core.command.impl.StatusCommand;
import com.javasleuth.core.command.impl.TraceCommand;
import com.javasleuth.core.command.impl.TtCommand;
import com.javasleuth.core.command.impl.VmToolCommand;
import com.javasleuth.core.command.impl.WatchCommand;
import com.javasleuth.foundation.security.CommandMeta;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class EnhancementCommandProvider {
    Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext context) {
        Instrumentation instrumentation = context.requireInstrumentation();
        List<CommandDescriptor> descriptors = new ArrayList<>();

        WatchCommand watchCommand = new WatchCommand(
            instrumentation,
            context.requireTransformer(),
            context.requireConfig(),
            context.requireJobManager(),
            context.requireSpyDispatcher(),
            context.requireEnhancementSessionRegistry()
        );
        descriptors.add(CommandDescriptor.ofSpec(watchCommand.getSpec(), watchCommand));

        TraceCommand traceCommand = new TraceCommand(
            instrumentation,
            context.requireTransformer(),
            context.requireConfig(),
            context.requireJobManager(),
            context.requireSpyDispatcher(),
            context.requireEnhancementSessionRegistry()
        );
        descriptors.add(CommandDescriptor.ofSpec(traceCommand.getSpec(), traceCommand));

        BuiltinCommandMetas.add(
            descriptors,
            "tt",
            new TtCommand(
                instrumentation,
                context.requireTransformer(),
                context.requireConfig(),
                context.requireJobManager(),
                context.requireSpyDispatcher(),
                context.requireEnhancementSessionRegistry()
            ),
            BuiltinCommandMetas.instrumentationStream()
        );
        BuiltinCommandMetas.add(descriptors, "jobs", new JobsCommand(context.requireJobManager()), CommandMeta.operator(true, false));
        descriptors.add(CommandDescriptor.ofSpec(EnhanceCommand.spec(), new EnhanceCommand(context.requireEnhancementSessionRegistry())));

        MonitorCommand monitorCommand = new MonitorCommand(
            instrumentation,
            context.requireTransformer(),
            context.requireJobManager(),
            context.requireSpyDispatcher(),
            context.requireEnhancementSessionRegistry()
        );
        descriptors.add(CommandDescriptor.ofSpec(monitorCommand.getSpec(), monitorCommand));

        BuiltinCommandMetas.add(
            descriptors,
            "stack",
            new StackCommand(
                instrumentation,
                context.requireTransformer(),
                context.requireConfig(),
                context.requireJobManager(),
                context.requireSpyDispatcher(),
                context.requireEnhancementSessionRegistry()
            ),
            BuiltinCommandMetas.instrumentationStream()
        );

        BuiltinCommandMetas.add(
            descriptors,
            "reset",
            new ResetCommand(
                instrumentation,
                context.requireTransformer(),
                context.requireJobManager(),
                context.requireVmToolSessionRegistry(),
                context.requireEnhancementSessionRegistry()
            ),
            CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(1)
        );

        BuiltinCommandMetas.add(
            descriptors,
            "status",
            new StatusCommand(
                instrumentation,
                context.requireMetricsCollector(),
                context.requireTransformer(),
                context.requireConfig(),
                context.requirePerformanceOptimizer(),
                context.requireSpyDispatcher(),
                context.requireEnhancementSessionRegistry()
            ),
            CommandMeta.viewer(false, false)
        );

        VmToolCommand vmtool = new VmToolCommand(
            instrumentation,
            context.requireTransformer(),
            context.requireConfig(),
            context.requireDangerousConfirm(),
            context.requireVmToolSessionRegistry(),
            context.requireEnhancementSessionRegistry()
        );
        descriptors.add(CommandDescriptor.ofSpec(vmtool.getSpec(), vmtool));

        return descriptors;
    }
}
