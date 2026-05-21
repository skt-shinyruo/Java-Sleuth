package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.StreamCommand;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.impl.tt.TtFormatter;
import com.javasleuth.core.command.impl.tt.TtRecordEngine;
import com.javasleuth.core.command.impl.tt.TtRecordStore;
import com.javasleuth.core.command.impl.tt.TtReplayTemplateGenerator;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.CommandSpecOptionTokens;
import com.javasleuth.core.command.spec.CommandSpecParser;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.command.spec.SubcommandSpec;
import com.javasleuth.core.agent.runtime.BootstrapBridge;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.bootstrap.data.TtRecord;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.bootstrap.util.SleuthValueFormatter;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.util.LoadedClassResolver;
import java.lang.instrument.Instrumentation;
import java.time.Instant;
import java.util.List;

public class TtCommand implements StreamCommand, SpecBackedCommand {
    private final TtRecordEngine recordEngine;
    private final TtReplayTemplateGenerator replayGenerator;
    private final JobManager jobManager;
    private final TtRecordStore recordStore;

    public TtCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager
    ) {
        this(instrumentation, transformer, config, jobManager, null);
    }

    public TtCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager,
        SleuthSpyDispatcher spyDispatcher
    ) {
        this(instrumentation, transformer, config, jobManager, spyDispatcher, null);
    }

    public TtCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager,
        SleuthSpyDispatcher spyDispatcher,
        EnhancementSessionRegistry sessionRegistry
    ) {
        if (jobManager == null) {
            throw new IllegalArgumentException("jobManager");
        }
        this.recordStore = new TtRecordStore();
        this.recordEngine = new TtRecordEngine(instrumentation, transformer, config, recordStore, spyDispatcher, sessionRegistry);
        this.replayGenerator = new TtReplayTemplateGenerator();
        this.jobManager = jobManager;
    }

    public static CommandSpec spec() {
        return recordOptions(CommandSpec.builder("tt")
            .description("Record, list, inspect, and replay method time-travel records (lite)")
            .usage("tt <class-pattern> <method-pattern> [options]")
            .meta(instrumentationStreamMeta())
            .argument(ArgumentSpec.required("class-pattern"))
            .argument(ArgumentSpec.required("method-pattern"))
            .unknownSubcommandAsArgument(true))
            .subcommand(SubcommandSpec.of(
                "record",
                "Record method invocations",
                recordOptions(CommandSpec.builder("record")
                    .description("Record method invocations")
                    .usage("tt record <class-pattern> <method-pattern> [options]")
                    .argument(ArgumentSpec.required("class-pattern"))
                    .argument(ArgumentSpec.required("method-pattern")))
                    .build()
            ))
            .subcommand(SubcommandSpec.of(
                "list",
                "List captured records",
                CommandSpec.builder("list")
                    .description("List captured records")
                    .usage("tt list [limit]")
                    .argument(ArgumentSpec.optional("limit"))
                    .build()
            ))
            .subcommand(SubcommandSpec.of(
                "detail",
                "Show record details",
                CommandSpec.builder("detail")
                    .description("Show record details")
                    .usage("tt detail <recordId>")
                    .argument(ArgumentSpec.required("record-id"))
                    .build()
            ))
            .subcommand(SubcommandSpec.of(
                "replay",
                "Generate a replay template for a record",
                CommandSpec.builder("replay")
                    .description("Generate a replay template for a record")
                    .usage("tt replay <recordId>")
                    .argument(ArgumentSpec.required("record-id"))
                    .build()
            ))
            .subcommand(SubcommandSpec.of(
                "clear",
                "Clear captured records",
                CommandSpec.builder("clear")
                    .description("Clear captured records")
                    .usage("tt clear")
                    .build()
            ))
            .subcommand(SubcommandSpec.of(
                "stop",
                "Stop an active TT recording session",
                CommandSpec.builder("stop")
                    .description("Stop an active TT recording session")
                    .usage("tt stop <ttId>")
                    .argument(ArgumentSpec.required("tt-id"))
                    .build()
            ))
            .example("tt com.example.* doWork -n 100")
            .example("tt record com.example.* doWork --bg")
            .example("tt detail 42")
            .build();
    }

    private static CommandSpec.Builder recordOptions(CommandSpec.Builder builder) {
        return builder
            .option(OptionSpec.integer("count").alias("-n").alias("--count").defaultValue(100).range(1, 100000).build())
            .option(OptionSpec.longNumber("timeout").alias("-t").alias("--timeout").defaultValue(30L).range(1, 86400).build())
            .option(OptionSpec.string("loader").alias("--loader").alias("--loader-id").alias("--loader-hash").build())
            .option(OptionSpec.flag("first").alias("--first").alias("--unsafe-first").build())
            .option(OptionSpec.integer("limit").alias("--limit").defaultValue(50).range(1, 10000).build())
            .option(OptionSpec.flag("bg").alias("--bg").build());
    }

    @Override
    public CommandSpec getSpec() {
        return spec();
    }

    @Override
    public String execute(String[] args) throws Exception {
        return runTt(args, null);
    }

    @Override
    public void executeStream(String[] args, StreamSink sink) throws Exception {
        runTt(args, sink);
    }

    private String runTt(String[] args, StreamSink sink) throws Exception {
        if (args == null || args.length == 1) {
            return getHelp();
        }

        ParsedCommand parsed = parsedOrFallback(args);
        if (parsed.isHelpRequested()) {
            return getHelp();
        }

        String sub = parsed.subcommandName();
        if (sub == null) {
            return runRecord(parsed, args, sink);
        }
        switch (sub) {
            case "list":
                return list(parsed);
            case "detail":
                return detail(parsed);
            case "replay":
                return replay(parsed);
            case "clear":
                recordStore.clear();
                return "TT records cleared.";
            case "stop":
                return stop(parsed);
            case "record":
                return runRecord(parsed, args, sink);
            default:
                return getHelp();
        }
    }

    private String runRecord(ParsedCommand parsed, String[] args, StreamSink sink) throws Exception {
        if (Boolean.TRUE.equals(parsed.booleanOption("bg"))) {
            String[] jobArgs = CommandSpecOptionTokens.removeOptionTokens(args, spec(), "bg");
            String commandLine = String.join(" ", jobArgs);
            String jobId = jobManager.submitStreamJob(
                "tt",
                commandLine,
                jobSink -> runTt(jobArgs, jobSink)
            );
            String msg = "Started tt in background. Job ID: " + jobId + " (use: jobs tail " + jobId + ")";
            if (sink != null) {
                sink.send(msg);
                sink.close("job started");
                return "";
            }
            return msg;
        }
        Integer loaderId = parseLoaderId(parsed.stringOption("loader"), sink);
        if (loaderId == INVALID_LOADER_ID) {
            return "";
        }

        return recordEngine.record(
            parsed.argument("class-pattern"),
            parsed.argument("method-pattern"),
            parsed.intOption("count"),
            parsed.longOption("timeout"),
            loaderId,
            Boolean.TRUE.equals(parsed.booleanOption("first")),
            parsed.intOption("limit"),
            sink
        );
    }

    private static final Integer INVALID_LOADER_ID = Integer.valueOf(Integer.MIN_VALUE);

    private static Integer parseLoaderId(String loaderRaw, StreamSink sink) {
        if (loaderRaw == null) {
            return null;
        }
        Integer loaderId = LoadedClassResolver.parseLoaderId(loaderRaw);
        if (loaderId != null) {
            return loaderId;
        }
        String msg = "Invalid --loader value: " + loaderRaw + " (expected: bootstrap/null/0x1234/1234)";
        if (sink != null) {
            sink.error(msg);
            return INVALID_LOADER_ID;
        }
        throw new IllegalArgumentException(msg);
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

    private String list(ParsedCommand parsed) {
        int n = parseInt(parsed.argument("limit"), 50);
        List<TtRecord> records = recordStore.list(n);
        if (records.isEmpty()) {
            return "No TT records.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ID\tTIME\tCOST\tTYPE\tMETHOD\tTHREAD\n");
        for (TtRecord r : records) {
            sb.append(r.getRecordId()).append("\t")
                .append(Instant.ofEpochMilli(r.getTimestampMs())).append("\t")
                .append(TtFormatter.formatNanos(r.getDuration())).append("\t")
                .append(r.getEventType()).append("\t")
                .append(r.getClassName()).append(".").append(r.getMethodName()).append("()\t")
                .append(r.getThreadName()).append("#").append(r.getThreadId())
                .append("\n");
        }
        return sb.toString().trim();
    }

    private String detail(ParsedCommand parsed) {
        long id;
        try {
            id = Long.parseLong(parsed.argument("record-id"));
        } catch (NumberFormatException e) {
            return "Invalid recordId: " + parsed.argument("record-id");
        }
        TtRecord r = recordStore.find(id);
        if (r == null) {
            return "Record not found: " + id;
        }

        SleuthValueFormatter.Options opt = new SleuthValueFormatter.Options()
            .withMaxDepth(2)
            .withMaxStringLength(200)
            .withMaxCollectionItems(20)
            .withMaxMapEntries(20);

        StringBuilder sb = new StringBuilder();
        sb.append("=== TT Record Detail ===\n");
        sb.append("RecordId: ").append(r.getRecordId()).append("\n");
        sb.append("Time: ").append(Instant.ofEpochMilli(r.getTimestampMs())).append("\n");
        sb.append("Thread: ").append(r.getThreadName()).append("#").append(r.getThreadId()).append("\n");
        sb.append("Method: ").append(r.getClassName()).append(".").append(r.getMethodName()).append(r.getMethodDescriptor()).append("\n");
        sb.append("Cost: ").append(TtFormatter.formatNanos(r.getDuration())).append("\n");
        sb.append("Type: ").append(r.getEventType()).append("\n\n");

        sb.append("Params: ").append(SleuthValueFormatter.format(r.getParameters(), opt)).append("\n");
        if (r.getEventType() == TtRecord.EventType.METHOD_EXCEPTION) {
            sb.append("Throw: ").append(SleuthValueFormatter.format(r.getException(), opt)).append("\n");
        } else {
            sb.append("Return: ").append(SleuthValueFormatter.format(r.getReturnValue(), opt)).append("\n");
        }
        return sb.toString().trim();
    }

    private String stop(ParsedCommand parsed) {
        String ttId = parsed.argument("tt-id");
        boolean ok = recordEngine.stop(ttId);
        if (!ok) {
            return "TT session not found: " + ttId;
        }
        return "TT stopped: " + ttId;
    }

    private String replay(ParsedCommand parsed) {
        long id;
        try {
            id = Long.parseLong(parsed.argument("record-id"));
        } catch (NumberFormatException e) {
            return "Invalid recordId: " + parsed.argument("record-id");
        }

        TtRecord r = recordStore.find(id);
        if (r == null) {
            return "Record not found: " + id;
        }
        return replayGenerator.generate(r);
    }

    private int parseInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String getHelp() {
        return CommandHelpRenderer.render(spec());
    }

    @Override
    public String getDescription() {
        return "TT-lite: record/list/detail/replay-template (no execution)";
    }
}
