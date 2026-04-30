package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.StreamCommand;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.impl.stack.StackTraceLiteEngine;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.CommandSpecOptionTokens;
import com.javasleuth.core.command.spec.CommandSpecParser;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.agent.runtime.BootstrapBridge;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import java.lang.instrument.Instrumentation;

/**
 * Method-triggered stack tracing, Arthas-style lite mode.
 */
public class StackCommand implements StreamCommand, SpecBackedCommand {
    private final StackTraceLiteEngine traceEngine;
    private final JobManager jobManager;

    public StackCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager
    ) {
        this(instrumentation, transformer, config, jobManager, null);
    }

    public StackCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager,
        SleuthSpyDispatcher spyDispatcher
    ) {
        this(instrumentation, transformer, config, jobManager, spyDispatcher, null);
    }

    public StackCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager,
        SleuthSpyDispatcher spyDispatcher,
        EnhancementSessionRegistry sessionRegistry
    ) {
        this.traceEngine = new StackTraceLiteEngine(instrumentation, transformer, config, spyDispatcher, sessionRegistry);
        if (jobManager == null) {
            throw new IllegalArgumentException("jobManager");
        }
        this.jobManager = jobManager;
    }

    public static CommandSpec spec() {
        return CommandSpec.builder("stack")
            .description("Trace call stacks when a matching method is invoked")
            .usage("stack <class-pattern> <method-pattern> [options]")
            .meta(instrumentationStreamMeta())
            .argument(ArgumentSpec.required("class-pattern"))
            .argument(ArgumentSpec.required("method-pattern"))
            .option(OptionSpec.integer("count").alias("-n").alias("--count").defaultValue(10).range(1, 100000).build())
            .option(OptionSpec.longNumber("timeout").alias("-t").alias("--timeout").defaultValue(30L).range(1, 86400).build())
            .option(OptionSpec.integer("depth").alias("--depth").defaultValue(20).range(1, 200).build())
            .option(OptionSpec.flag("bg").alias("--bg").build())
            .example("stack com.example.* doWork -n 5 --depth 30")
            .example("stack *Service* *method* -t 60")
            .example("stack com.example.* doWork --bg")
            .build();
    }

    @Override
    public CommandSpec getSpec() {
        return spec();
    }

    @Override
    public String execute(String[] args) throws Exception {
        return runStack(args, null);
    }

    @Override
    public void executeStream(String[] args, StreamSink sink) throws Exception {
        runStack(args, sink);
    }

    private String runStack(String[] args, StreamSink sink) throws Exception {
        if (args == null || args.length < 2) {
            return CommandHelpRenderer.render(spec());
        }

        ParsedCommand parsed = parsedOrFallback(args);
        if (parsed.isHelpRequested()) {
            return CommandHelpRenderer.render(spec());
        }

        if (Boolean.TRUE.equals(parsed.booleanOption("bg"))) {
            String[] jobArgs = CommandSpecOptionTokens.removeOptionTokens(args, spec(), "bg");
            String commandLine = String.join(" ", jobArgs);
            String jobId = jobManager.submitStreamJob(
                "stack",
                commandLine,
                jobSink -> runStack(jobArgs, jobSink)
            );
            String msg = "Started stack in background. Job ID: " + jobId + " (use: jobs tail " + jobId + ")";
            if (sink != null) {
                sink.send(msg);
                sink.close("job started");
                return "";
            }
            return msg;
        }

        return traceEngine.start(
            parsed.argument("class-pattern"),
            parsed.argument("method-pattern"),
            parsed.intOption("count"),
            parsed.longOption("timeout"),
            parsed.intOption("depth"),
            sink
        );
    }

    private ParsedCommand parsedOrFallback(String[] args) {
        CommandContext ctx = CommandContextHolder.get();
        ParsedCommand parsed = ctx != null ? ctx.getParsedCommand() : null;
        if (parsed != null && Boolean.TRUE.equals(parsed.booleanOption("bg")) && !CommandSpecOptionTokens.hasOptionToken(args, spec(), "bg")) {
            return CommandSpecParser.parse(spec(), args);
        }
        return parsed != null ? parsed : CommandSpecParser.parse(spec(), args);
    }

    private static CommandMeta instrumentationStreamMeta() {
        return CommandMeta.operator(false, true)
            .requiresBootstrap(BootstrapBridge.SPY_API)
            .withCapability(CommandCapability.LONG_RUNNING);
    }

    @Override
    public String getDescription() {
        return "Stack sampling and Arthas-like stack trace (lite)";
    }
}
