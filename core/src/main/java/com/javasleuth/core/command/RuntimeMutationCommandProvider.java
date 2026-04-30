package com.javasleuth.core.command;

import com.javasleuth.core.command.impl.DumpCommand;
import com.javasleuth.core.command.impl.HeapDumpCommand;
import com.javasleuth.core.command.impl.JadCommand;
import com.javasleuth.core.command.impl.MemoryCompilerCommand;
import com.javasleuth.core.command.impl.RedefineCommand;
import com.javasleuth.core.command.impl.RetransformCommand;
import com.javasleuth.core.command.impl.SysEnvCommand;
import com.javasleuth.core.command.impl.SysPropCommand;
import com.javasleuth.core.command.impl.VmOptionCommand;
import com.javasleuth.foundation.security.AuthenticationManager.UserRole;
import com.javasleuth.foundation.security.CommandMeta;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class RuntimeMutationCommandProvider {
    Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext context) {
        Instrumentation instrumentation = context.requireInstrumentation();
        List<CommandDescriptor> descriptors = new ArrayList<>();

        BuiltinCommandMetas.add(
            descriptors,
            "redefine",
            new RedefineCommand(instrumentation),
            CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(3)
        );
        BuiltinCommandMetas.add(
            descriptors,
            "mc",
            new MemoryCompilerCommand(),
            BuiltinCommandMetas.writesDisk(
                CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(3)
            )
        );
        BuiltinCommandMetas.add(
            descriptors,
            "retransform",
            new RetransformCommand(instrumentation),
            CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(3)
        );
        BuiltinCommandMetas.add(
            descriptors,
            "sysprop",
            new SysPropCommand(instrumentation, context.requireConfig()),
            CommandMeta.viewer(true, false).withSubcommandRole("set", UserRole.ADMIN)
        );
        BuiltinCommandMetas.add(descriptors, "sysenv", new SysEnvCommand(instrumentation), CommandMeta.viewer(true, false));
        VmOptionCommand vmOptionCommand = new VmOptionCommand(instrumentation);
        descriptors.add(CommandDescriptor.ofSpec(vmOptionCommand.getSpec(), vmOptionCommand));
        BuiltinCommandMetas.add(
            descriptors,
            "heapdump",
            new HeapDumpCommand(instrumentation, context.requirePerformanceOptimizer()),
            BuiltinCommandMetas.writesDisk(
                CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(2)
            )
        );
        BuiltinCommandMetas.add(
            descriptors,
            "dump",
            new DumpCommand(instrumentation),
            BuiltinCommandMetas.writesDisk(
                CommandMeta.operator(false, false).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(5)
            )
        );
        BuiltinCommandMetas.add(
            descriptors,
            "jad",
            new JadCommand(instrumentation),
            CommandMeta.operator(false, false).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(5)
        );
        return descriptors;
    }
}
