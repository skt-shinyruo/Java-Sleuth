package com.javasleuth.command;

import com.javasleuth.command.impl.*;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.security.AuditLogger;
import com.javasleuth.security.AuthenticationManager.UserRole;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;

public class BuiltinCommandProvider implements CommandProvider {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final MetricsCollector metricsCollector;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;

    public BuiltinCommandProvider(Instrumentation instrumentation,
                                  SleuthClassFileTransformer transformer,
                                  MetricsCollector metricsCollector,
                                  ProductionConfig config,
                                  AuditLogger auditLogger) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.metricsCollector = metricsCollector;
        this.config = config;
        this.auditLogger = auditLogger;
    }

    @Override
    public String getName() {
        return "builtin";
    }

    @Override
    public Map<String, Command> getCommands() {
        Map<String, Command> commands = new HashMap<>();

        commands.put("dashboard", new DashboardCommand(instrumentation));
        commands.put("thread", new ThreadCommand(instrumentation));
        commands.put("sc", new SearchClassCommand(instrumentation));
        commands.put("sm", new SearchMethodCommand(instrumentation));
        commands.put("watch", new WatchCommand(instrumentation, transformer));
        commands.put("trace", new TraceCommand(instrumentation, transformer));
        commands.put("tt", new TtCommand(instrumentation, transformer));
        commands.put("jobs", new JobsCommand());
        commands.put("redefine", new RedefineCommand(instrumentation));
        commands.put("mc", new MemoryCompilerCommand());
        commands.put("retransform", new RetransformCommand(instrumentation));

        commands.put("profiler", new ProfilerCommand(instrumentation));
        commands.put("monitor", new MonitorCommand(instrumentation, transformer));
        commands.put("stack", new StackCommand(instrumentation, transformer));
        commands.put("reset", new ResetCommand(instrumentation, transformer));

        commands.put("jvm", new JvmCommand(instrumentation));
        commands.put("sysprop", new SysPropCommand(instrumentation));
        commands.put("sysenv", new SysEnvCommand(instrumentation));
        commands.put("vmoption", new VmOptionCommand(instrumentation));
        commands.put("memory", new MemoryCommand(instrumentation));
        commands.put("heapdump", new HeapDumpCommand(instrumentation));
        commands.put("dump", new DumpCommand(instrumentation));
        commands.put("getstatic", new GetStaticCommand(instrumentation));

        commands.put("jad", new JadCommand(instrumentation));
        commands.put("classloader", new ClassLoaderCommand(instrumentation));
        commands.put("mbean", new MBeanCommand(instrumentation));
        commands.put("logger", new LoggerCommand());
        commands.put("vmtool", new VmToolCommand(instrumentation, transformer));

        commands.put("health", new HealthCommand(metricsCollector));
        commands.put("metrics", new MetricsCommand(metricsCollector));
        commands.put("status", new StatusCommand(instrumentation, metricsCollector, transformer));
        commands.put("config", new ConfigCommand(config));
        commands.put("audit", new AuditCommand(auditLogger));
        commands.put("session", new SessionCommand());
        commands.put("perm", new PermCommand());
        commands.put("version", new VersionCommand());

        commands.put("quit", new QuitCommand());
        commands.put("auth", new AuthCommand());
        commands.put("stop", new StopCommand());

        return commands;
    }

    @Override
    public Map<String, CommandMeta> getCommandMeta() {
        Map<String, CommandMeta> meta = new HashMap<>();

        meta.put("help", CommandMeta.viewer(true, false));
        meta.put("sc", CommandMeta.viewer(true, false).withImpact(CommandMeta.ImpactLevel.MEDIUM));
        meta.put("sm", CommandMeta.viewer(true, false).withImpact(CommandMeta.ImpactLevel.MEDIUM));
        meta.put("jad", CommandMeta.operator(false, false).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(5));
        meta.put("classloader", CommandMeta.viewer(true, false).withImpact(CommandMeta.ImpactLevel.MEDIUM));

        meta.put("dashboard", CommandMeta.viewer(true, false));
        meta.put("thread", CommandMeta.viewer(false, false));
        meta.put("memory", CommandMeta.viewer(true, false));
        meta.put("jvm", CommandMeta.viewer(true, false));
        meta.put("health", CommandMeta.viewer(false, false));
        meta.put("metrics", CommandMeta.viewer(false, false));
        meta.put("status", CommandMeta.viewer(false, false));
        meta.put("sysprop", CommandMeta.viewer(true, false).withSubcommandRole("set", UserRole.ADMIN));
        meta.put("sysenv", CommandMeta.viewer(true, false));
        meta.put("vmoption", CommandMeta.operator(true, false).withSubcommandRole("set", UserRole.ADMIN));
        meta.put("mbean", CommandMeta.operator(false, false));
        meta.put("session", CommandMeta.viewer(false, false));
        meta.put("perm", CommandMeta.viewer(true, false));
        meta.put("version", CommandMeta.viewer(true, false));

        meta.put("watch", CommandMeta.operator(false, true));
        meta.put("trace", CommandMeta.operator(false, true));
        meta.put("monitor", CommandMeta.operator(false, true));
        meta.put("tt", CommandMeta.operator(false, true));
        meta.put("jobs", CommandMeta.operator(true, false));
        meta.put("dump", CommandMeta.operator(false, false).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(5));
        meta.put("getstatic", CommandMeta.operator(false, false));
        meta.put("logger", CommandMeta.operator(true, false));
        meta.put("profiler", CommandMeta.operator(false, false));
        meta.put("stack", CommandMeta.operator(false, true));

        meta.put("redefine", CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(3));
        meta.put("retransform", CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(3));
        meta.put("mc", CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(3));
        meta.put("heapdump", CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(2));
        meta.put("reset", CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(1));
        meta.put("stop", CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(1));

        meta.put("config", CommandMeta.admin(false, false));
        meta.put("audit", CommandMeta.admin(false, false).withAudit(false));
        meta.put("quit", CommandMeta.viewer(false, false));
        meta.put("auth", CommandMeta.viewer(false, false));

        meta.put("vmtool", CommandMeta.operator(false, false)
            .withImpact(CommandMeta.ImpactLevel.MEDIUM)
            .withRateLimit(10)
            .withSubcommandRole("invoke", UserRole.ADMIN)
            .withSubcommandRole("invoke-static", UserRole.ADMIN)
            .withSubcommandRole("invokestatic", UserRole.ADMIN));

        return meta;
    }
}
