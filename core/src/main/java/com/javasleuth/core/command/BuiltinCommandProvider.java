package com.javasleuth.core.command;

import com.javasleuth.core.command.impl.*;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.AuthenticationManager.UserRole;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;

public class BuiltinCommandProvider implements CommandProvider {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final MetricsCollector metricsCollector;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final Runnable shutdownHook;
    private final AuthenticationManager authenticationManager;
    private final DangerousCommandConfirmationManager dangerousConfirm;
    private final JobManager jobManager;
    private final VmToolSessionRegistry vmToolSessionRegistry;
    private final PerformanceOptimizer performanceOptimizer;

    public BuiltinCommandProvider(Instrumentation instrumentation,
                                  SleuthClassFileTransformer transformer,
                                  MetricsCollector metricsCollector,
                                  ProductionConfig config,
                                  AuditLogger auditLogger,
                                  Runnable shutdownHook,
                                  AuthenticationManager authenticationManager,
                                  DangerousCommandConfirmationManager dangerousConfirm,
                                  JobManager jobManager,
                                  VmToolSessionRegistry vmToolSessionRegistry,
                                  PerformanceOptimizer performanceOptimizer) {
        if (authenticationManager == null) {
            throw new IllegalArgumentException("authenticationManager is required");
        }
        if (dangerousConfirm == null) {
            throw new IllegalArgumentException("dangerousConfirm is required");
        }
        if (jobManager == null) {
            throw new IllegalArgumentException("jobManager is required");
        }
        if (vmToolSessionRegistry == null) {
            throw new IllegalArgumentException("vmToolSessionRegistry is required");
        }
        if (performanceOptimizer == null) {
            throw new IllegalArgumentException("performanceOptimizer is required");
        }
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.metricsCollector = metricsCollector;
        this.config = config;
        this.auditLogger = auditLogger;
        this.shutdownHook = shutdownHook;
        this.authenticationManager = authenticationManager;
        this.dangerousConfirm = dangerousConfirm;
        this.jobManager = jobManager;
        this.vmToolSessionRegistry = vmToolSessionRegistry;
        this.performanceOptimizer = performanceOptimizer;
    }

    @Override
    public String getName() {
        return "builtin";
    }

    @Override
    public Map<String, Command> getCommands() {
        Map<String, Command> commands = new HashMap<>();

        JobManager jobManager = this.jobManager;
        VmToolSessionRegistry vmToolSessionRegistry = this.vmToolSessionRegistry;
        PerformanceOptimizer performanceOptimizer = this.performanceOptimizer;

        commands.put("dashboard", new DashboardCommand(instrumentation));
        commands.put("thread", new ThreadCommand(instrumentation));
        commands.put("sc", new SearchClassCommand(instrumentation));
        commands.put("sm", new SearchMethodCommand(instrumentation));
        commands.put("watch", new WatchCommand(instrumentation, transformer, config, jobManager));
        commands.put("trace", new TraceCommand(instrumentation, transformer, config, jobManager));
        commands.put("tt", new TtCommand(instrumentation, transformer, config, jobManager));
        commands.put("jobs", new JobsCommand(jobManager));
        commands.put("redefine", new RedefineCommand(instrumentation));
        commands.put("mc", new MemoryCompilerCommand());
        commands.put("retransform", new RetransformCommand(instrumentation));

        commands.put("profiler", new ProfilerCommand(instrumentation));
        commands.put("monitor", new MonitorCommand(instrumentation, transformer, jobManager));
        commands.put("stack", new StackCommand(instrumentation, transformer, config, jobManager));
        commands.put("reset", new ResetCommand(instrumentation, transformer, jobManager, vmToolSessionRegistry));

        commands.put("jvm", new JvmCommand(instrumentation));
        commands.put("sysprop", new SysPropCommand(instrumentation, config));
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
        commands.put("vmtool", new VmToolCommand(instrumentation, transformer, config, dangerousConfirm, vmToolSessionRegistry));

        commands.put("health", new HealthCommand(metricsCollector, performanceOptimizer));
        commands.put("metrics", new MetricsCommand(metricsCollector));
        commands.put("status", new StatusCommand(instrumentation, metricsCollector, transformer, config, performanceOptimizer));
        commands.put("config", new ConfigCommand(config));
        commands.put("audit", new AuditCommand(auditLogger));
        commands.put("session", new SessionCommand(authenticationManager));
        commands.put("perm", new PermCommand());
        commands.put("version", new VersionCommand());

        commands.put("quit", new QuitCommand());
        commands.put("auth", new AuthCommand(authenticationManager));
        commands.put("stop", new StopCommand(shutdownHook));

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
