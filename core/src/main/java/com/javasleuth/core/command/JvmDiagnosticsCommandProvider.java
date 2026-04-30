package com.javasleuth.core.command;

import com.javasleuth.core.command.impl.ClassLoaderCommand;
import com.javasleuth.core.command.impl.DashboardCommand;
import com.javasleuth.core.command.impl.GetStaticCommand;
import com.javasleuth.core.command.impl.JvmCommand;
import com.javasleuth.core.command.impl.LoggerCommand;
import com.javasleuth.core.command.impl.MBeanCommand;
import com.javasleuth.core.command.impl.MemoryCommand;
import com.javasleuth.core.command.impl.ProfilerCommand;
import com.javasleuth.core.command.impl.SearchClassCommand;
import com.javasleuth.core.command.impl.SearchMethodCommand;
import com.javasleuth.core.command.impl.ThreadCommand;
import com.javasleuth.foundation.security.CommandMeta;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class JvmDiagnosticsCommandProvider {
    Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext context) {
        Instrumentation instrumentation = context.requireInstrumentation();
        List<CommandDescriptor> descriptors = new ArrayList<>();
        BuiltinCommandMetas.add(descriptors, "dashboard", new DashboardCommand(instrumentation), CommandMeta.viewer(true, false));
        BuiltinCommandMetas.add(descriptors, "thread", new ThreadCommand(instrumentation), CommandMeta.viewer(false, false));
        BuiltinCommandMetas.add(
            descriptors,
            "sc",
            new SearchClassCommand(instrumentation),
            CommandMeta.viewer(true, false).withImpact(CommandMeta.ImpactLevel.MEDIUM)
        );
        BuiltinCommandMetas.add(
            descriptors,
            "sm",
            new SearchMethodCommand(instrumentation),
            CommandMeta.viewer(true, false).withImpact(CommandMeta.ImpactLevel.MEDIUM)
        );
        BuiltinCommandMetas.add(descriptors, "profiler", new ProfilerCommand(instrumentation), CommandMeta.operator(false, false));
        BuiltinCommandMetas.add(descriptors, "jvm", new JvmCommand(instrumentation), CommandMeta.viewer(true, false));
        BuiltinCommandMetas.add(descriptors, "memory", new MemoryCommand(instrumentation), CommandMeta.viewer(true, false));
        BuiltinCommandMetas.add(descriptors, "getstatic", new GetStaticCommand(instrumentation), CommandMeta.operator(false, false));
        BuiltinCommandMetas.add(
            descriptors,
            "classloader",
            new ClassLoaderCommand(instrumentation),
            CommandMeta.viewer(true, false).withImpact(CommandMeta.ImpactLevel.MEDIUM)
        );
        BuiltinCommandMetas.add(descriptors, "mbean", new MBeanCommand(instrumentation), CommandMeta.operator(false, false));
        BuiltinCommandMetas.add(descriptors, "logger", new LoggerCommand(), CommandMeta.operator(true, false));
        return descriptors;
    }
}
