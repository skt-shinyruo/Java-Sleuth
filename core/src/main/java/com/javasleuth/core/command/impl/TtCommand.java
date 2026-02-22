package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.StreamCommand;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.impl.tt.TtFormatter;
import com.javasleuth.core.command.impl.tt.TtRecordEngine;
import com.javasleuth.core.command.impl.tt.TtRecordParser;
import com.javasleuth.core.command.impl.tt.TtReplayTemplateGenerator;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.bootstrap.data.TtRecord;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.bootstrap.monitor.TtInterceptor;
import com.javasleuth.bootstrap.util.SleuthValueFormatter;
import java.lang.instrument.Instrumentation;
import java.time.Instant;
import java.util.List;

public class TtCommand implements StreamCommand {
    private final TtRecordParser recordParser;
    private final TtRecordEngine recordEngine;
    private final TtReplayTemplateGenerator replayGenerator;
    private final JobManager jobManager;

    public TtCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        JobManager jobManager
    ) {
        if (jobManager == null) {
            throw new IllegalArgumentException("jobManager");
        }
        this.recordParser = new TtRecordParser();
        this.recordEngine = new TtRecordEngine(instrumentation, transformer, config);
        this.replayGenerator = new TtReplayTemplateGenerator();
        this.jobManager = jobManager;
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

        // Subcommands: list/detail/replay/stop/clear/record
        String sub = args[1].toLowerCase();
        switch (sub) {
            case "list":
                return list(args);
            case "detail":
                return detail(args);
            case "replay":
                return replay(args);
            case "clear":
                TtInterceptor.clear();
                return "TT records cleared.";
            case "stop":
                return stop(args);
            case "record":
                // tt record <class-pattern> <method-pattern> [options]
                if (args.length < 4) {
                    return "Usage: tt record <class-pattern> <method-pattern> [options]";
                }
                String[] shifted = new String[args.length - 1];
                shifted[0] = "tt";
                System.arraycopy(args, 2, shifted, 1, args.length - 2);
                return runRecord(shifted, sink);
            default:
                break;
        }

        // Default: tt <class-pattern> <method-pattern>
        return runRecord(args, sink);
    }

    private String runRecord(String[] args, StreamSink sink) throws Exception {
        if (args == null || args.length < 3) {
            return getHelp();
        }

        TtRecordParser.ParseResult parsed = recordParser.parse(args);
        if (parsed.isInvalid() || parsed.isShowHelp()) {
            return getHelp();
        }

        if (parsed.isBackground()) {
            String[] jobArgs = parsed.getSanitizedArgs();
            String commandLine = String.join(" ", jobArgs);
            String jobId = jobManager.submitStreamJob(
                "tt",
                commandLine,
                jobSink -> runRecord(jobArgs, jobSink)
            );
            String msg = "Started tt in background. Job ID: " + jobId + " (use: jobs tail " + jobId + ")";
            if (sink != null) {
                sink.send(msg);
                sink.close("job started");
                return "";
            }
            return msg;
        }

        return recordEngine.record(
            parsed.getClassPattern(),
            parsed.getMethodPattern(),
            parsed.getMaxCount(),
            parsed.getTimeoutSeconds(),
            sink
        );
    }

    private String list(String[] args) {
        int n = 50;
        if (args.length >= 3) {
            n = parseInt(args[2], 50);
        }
        List<TtRecord> records = TtInterceptor.list(n);
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

    private String detail(String[] args) {
        if (args.length < 3) {
            return "Usage: tt detail <recordId>";
        }
        long id;
        try {
            id = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return "Invalid recordId: " + args[2];
        }
        TtRecord r = TtInterceptor.find(id);
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

    private String stop(String[] args) {
        if (args.length < 3) {
            return "Usage: tt stop <ttId>";
        }
        String ttId = args[2];
        boolean ok = recordEngine.stop(ttId);
        if (!ok) {
            return "TT session not found: " + ttId;
        }
        return "TT stopped: " + ttId;
    }

    private String replay(String[] args) {
        if (args.length < 3) {
            return "Usage: tt replay <recordId>";
        }
        long id;
        try {
            id = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return "Invalid recordId: " + args[2];
        }

        TtRecord r = TtInterceptor.find(id);
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
        return "TT (lite) command usage:\n" +
            "  tt <class-pattern> <method-pattern> [options]\n" +
            "  tt record <class-pattern> <method-pattern> [options]\n" +
            "  tt list [n]\n" +
            "  tt detail <recordId>\n" +
            "  tt replay <recordId>            - Generate replay template (lite, no execution)\n" +
            "  tt clear\n" +
            "  tt stop <ttId>\n\n" +
            "Options:\n" +
            "  -n, --count <num>     Max records to capture (default: 100)\n" +
            "  -t, --timeout <sec>   Timeout in seconds (default: 30)\n" +
            "  --bg                 Run in background (use jobs tail/stop)\n";
    }

    @Override
    public String getDescription() {
        return "TT-lite: record/list/detail/replay-template (no execution)";
    }
}
