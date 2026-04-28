package com.javasleuth.core.command;

import com.javasleuth.core.command.impl.*;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.AuthenticationManager.UserRole;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class BuiltinCommandProvider implements CommandProvider {
    @Override
    public String getName() {
        return "builtin";
    }

    @Override
    public CommandProviderInfo getInfo() {
        return CommandProviderInfo.builtin(
            "builtin",
            Collections.singletonList("core-diagnostics")
        );
    }

    @Override
    public Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext context) {
        Instrumentation instrumentation = context.requireInstrumentation();
        List<CommandDescriptor> descriptors = new ArrayList<>();

        add(descriptors, "dashboard", new DashboardCommand(instrumentation), CommandMeta.viewer(true, false));
        add(descriptors, "thread", new ThreadCommand(instrumentation), CommandMeta.viewer(false, false));
        add(descriptors, "sc", new SearchClassCommand(instrumentation), CommandMeta.viewer(true, false).withImpact(CommandMeta.ImpactLevel.MEDIUM));
        add(descriptors, "sm", new SearchMethodCommand(instrumentation), CommandMeta.viewer(true, false).withImpact(CommandMeta.ImpactLevel.MEDIUM));
        add(
            descriptors,
            "watch",
            new WatchCommand(
                instrumentation,
                context.requireTransformer(),
                context.requireConfig(),
                context.requireJobManager(),
                context.requireSpyDispatcher(),
                context.requireEnhancementSessionRegistry()
            ),
            CommandMeta.operator(false, true)
        );
        add(
            descriptors,
            "trace",
            new TraceCommand(
                instrumentation,
                context.requireTransformer(),
                context.requireConfig(),
                context.requireJobManager(),
                context.requireSpyDispatcher(),
                context.requireEnhancementSessionRegistry()
            ),
            CommandMeta.operator(false, true)
        );
        add(
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
            CommandMeta.operator(false, true)
        );
        add(descriptors, "jobs", new JobsCommand(context.requireJobManager()), CommandMeta.operator(true, false));
        add(
            descriptors,
            "redefine",
            new RedefineCommand(instrumentation),
            CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(3)
        );
        add(
            descriptors,
            "mc",
            new MemoryCompilerCommand(),
            CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(3)
        );
        add(
            descriptors,
            "retransform",
            new RetransformCommand(instrumentation),
            CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(3)
        );

        add(descriptors, "profiler", new ProfilerCommand(instrumentation), CommandMeta.operator(false, false));
        add(
            descriptors,
            "monitor",
            new MonitorCommand(
                instrumentation,
                context.requireTransformer(),
                context.requireJobManager(),
                context.requireSpyDispatcher(),
                context.requireEnhancementSessionRegistry()
            ),
            CommandMeta.operator(false, true)
        );
        add(
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
            CommandMeta.operator(false, true)
        );
        add(
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

        add(descriptors, "jvm", new JvmCommand(instrumentation), CommandMeta.viewer(true, false));
        add(
            descriptors,
            "sysprop",
            new SysPropCommand(instrumentation, context.requireConfig()),
            CommandMeta.viewer(true, false).withSubcommandRole("set", UserRole.ADMIN)
        );
        add(descriptors, "sysenv", new SysEnvCommand(instrumentation), CommandMeta.viewer(true, false));
        add(
            descriptors,
            "vmoption",
            new VmOptionCommand(instrumentation),
            CommandMeta.operator(true, false).withSubcommandRole("set", UserRole.ADMIN)
        );
        add(descriptors, "memory", new MemoryCommand(instrumentation), CommandMeta.viewer(true, false));
        add(
            descriptors,
            "heapdump",
            new HeapDumpCommand(instrumentation, context.requirePerformanceOptimizer()),
            CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(2)
        );
        add(
            descriptors,
            "dump",
            new DumpCommand(instrumentation),
            CommandMeta.operator(false, false).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(5)
        );
        add(descriptors, "getstatic", new GetStaticCommand(instrumentation), CommandMeta.operator(false, false));

        add(
            descriptors,
            "jad",
            new JadCommand(instrumentation),
            CommandMeta.operator(false, false).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(5)
        );
        add(
            descriptors,
            "classloader",
            new ClassLoaderCommand(instrumentation),
            CommandMeta.viewer(true, false).withImpact(CommandMeta.ImpactLevel.MEDIUM)
        );
        add(descriptors, "mbean", new MBeanCommand(instrumentation), CommandMeta.operator(false, false));
        add(descriptors, "logger", new LoggerCommand(), CommandMeta.operator(true, false));
        add(
            descriptors,
            "vmtool",
            new VmToolCommand(
                instrumentation,
                context.requireTransformer(),
                context.requireConfig(),
                context.requireDangerousConfirm(),
                context.requireVmToolSessionRegistry(),
                context.requireEnhancementSessionRegistry()
            ),
            CommandMeta.operator(false, false)
                .withImpact(CommandMeta.ImpactLevel.MEDIUM)
                .withRateLimit(10)
                .withSubcommandRole("invoke", UserRole.ADMIN)
                .withSubcommandRole("invoke-static", UserRole.ADMIN)
                .withSubcommandRole("invokestatic", UserRole.ADMIN)
        );

        add(
            descriptors,
            "health",
            new HealthCommand(context.requireMetricsCollector(), context.requirePerformanceOptimizer()),
            CommandMeta.viewer(false, false)
        );
        add(descriptors, "metrics", new MetricsCommand(context.requireMetricsCollector()), CommandMeta.viewer(false, false));
        add(
            descriptors,
            "status",
            new StatusCommand(
                instrumentation,
                context.requireMetricsCollector(),
                context.requireTransformer(),
                context.requireConfig(),
                context.requirePerformanceOptimizer(),
                context.requireSpyDispatcher()
            ),
            CommandMeta.viewer(false, false)
        );
        add(descriptors, "config", new ConfigCommand(context.requireConfig()), CommandMeta.admin(false, false));
        add(descriptors, "audit", new AuditCommand(context.requireAuditLogger()), CommandMeta.admin(false, false).withAudit(false));
        add(descriptors, "session", new SessionCommand(context.requireAuthenticationManager()), CommandMeta.viewer(false, false));
        add(descriptors, "perm", new PermCommand(), CommandMeta.viewer(true, false));
        add(descriptors, "version", new VersionCommand(), CommandMeta.viewer(true, false));

        add(descriptors, "quit", new QuitCommand(), CommandMeta.viewer(false, false));
        add(descriptors, "auth", new AuthCommand(context.requireAuthenticationManager()), CommandMeta.viewer(false, false));
        add(
            descriptors,
            "stop",
            new StopCommand(context.getShutdownHook()),
            CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(1)
        );

        return descriptors;
    }

    private static void add(List<CommandDescriptor> descriptors, String name, Command command, CommandMeta meta) {
        descriptors.add(CommandDescriptor.of(name, command, meta));
    }
}
