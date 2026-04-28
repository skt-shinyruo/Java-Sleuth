package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.StreamCommand;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.impl.stack.StackTraceLiteEngine;
import com.javasleuth.core.command.impl.stack.StackTraceLiteParser;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;

/**
 * StackCommand 提供两类能力：
 * 1) 线程栈采样/分析（legacy stack monitor/dump/analyze/...）
 * 2) 方法触发栈追踪（Arthas 风格简化版）：stack <class-pattern> <method-pattern> [options]
 */
public class StackCommand implements StreamCommand {
    private final StackTraceLiteParser traceParser;
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
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        this.traceParser = new StackTraceLiteParser();
        this.traceEngine = new StackTraceLiteEngine(instrumentation, transformer, config, spyDispatcher, sessionRegistry);
        if (jobManager == null) {
            throw new IllegalArgumentException("jobManager");
        }
        this.jobManager = jobManager;
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
            return getUsage();
        }

        String sub = args[1];
        if ("-h".equals(sub) || "--help".equals(sub) || "help".equalsIgnoreCase(sub)) {
            return getUsage();
        }

        String action = sub.toLowerCase(Locale.ROOT);
        // Arthas-like: stack <class-pattern> <method-pattern> [options]
        if (args.length < 3) {
            return getTraceHelp();
        }

        StackTraceLiteParser.ParseResult parsed = traceParser.parse(args);
        if (parsed.isInvalid() || parsed.isShowHelp()) {
            return getTraceHelp();
        }

        if (parsed.isBackground()) {
            String[] jobArgs = parsed.getSanitizedArgs();
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
            parsed.getClassPattern(),
            parsed.getMethodPattern(),
            parsed.getMaxCount(),
            parsed.getTimeoutSeconds(),
            parsed.getDepth(),
            sink
        );
    }

    private String getUsage() {
        return "Stack command usage:\n" +
            "  stack <class-pattern> <method-pattern> [options]   - Trace call stacks when method is invoked (lite)\n" +
            "  stack dump [thread-id]                            - Dump stack traces (all threads or specific)\n" +
            "  stack monitor start [intervalMs]                  - Start continuous monitoring (default 1000ms, range: 10-600000)\n" +
            "  stack monitor stop                                - Stop monitoring\n" +
            "  stack monitor status                              - Show monitoring status\n" +
            "  stack analyze [limit]                             - Analyze collected stack patterns (default 10, range: 1-200)\n" +
            "  stack blocked                                     - Show blocked/waiting threads\n" +
            "  stack deadlock                                    - Check for deadlocks\n" +
            "  stack hot [limit]                                 - Show hottest stack traces (default 5, range: 1-50)\n" +
            "  stack stats                                       - Show stack statistics\n" +
            "  stack clear                                       - Clear collected data\n" +
            "\n" +
            "Stack trace options:\n" +
            "  -n, --count <num>     Max events to capture (default: 10)\n" +
            "  -t, --timeout <sec>   Timeout in seconds (default: 30)\n" +
            "  --depth <frames>      Max stack frames to print (default: 20, max: 200)\n" +
            "  --bg                 Run in background (use jobs tail/stop)\n" +
            "\n" +
            "Examples:\n" +
            "  stack com.example.* doWork -n 5 --depth 30\n" +
            "  stack com.example.* doWork --bg\n" +
            "  stack dump\n" +
            "  stack monitor start 500\n" +
            "  stack analyze 20";
    }

    @Override
    public String getDescription() {
        return "Stack sampling and Arthas-like stack trace (lite)";
    }

    private String getTraceHelp() {
        return "Stack trace (lite) usage:\n" +
            "  stack <class-pattern> <method-pattern> [options]\n\n" +
            "Options:\n" +
            "  -n, --count <num>     Max events to capture (default: 10)\n" +
            "  -t, --timeout <sec>   Timeout in seconds (default: 30)\n" +
            "  --depth <frames>      Max stack frames to print (default: 20, max: 200)\n" +
            "  --bg                 Run in background (use jobs tail/stop)\n" +
            "  -h, --help           Show this help\n\n" +
            "Examples:\n" +
            "  stack com.example.* doWork -n 5 --depth 30\n" +
            "  stack *Service* *method* -t 60\n";
    }
}
