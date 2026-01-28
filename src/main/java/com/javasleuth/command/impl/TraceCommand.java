package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.command.StreamCommand;
import com.javasleuth.command.StreamSink;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.enhancement.ClassEnhancer;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.enhancement.TraceEnhancer;
import com.javasleuth.monitor.TraceInterceptor;
import com.javasleuth.data.TraceResult;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class TraceCommand implements StreamCommand {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ProductionConfig config;
    private final ConcurrentHashMap<String, TraceSession> activeSessions = new ConcurrentHashMap<>();

    public TraceCommand(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = ProductionConfig.getInstance();
    }

    @Override
    public String execute(String[] args) throws Exception {
        return runTrace(args, null);
    }

    @Override
    public void executeStream(String[] args, StreamSink sink) throws Exception {
        runTrace(args, sink);
    }

    private String runTrace(String[] args, StreamSink sink) throws Exception {
        if (args.length < 3) {
            String help = getHelp();
            if (sink != null) {
                sink.error(help);
                return "";
            }
            return help;
        }

        String classPattern = args[1];
        String methodPattern = args[2];

        // Parse options
        int maxDepth = 10;
        int maxCount = 100;
        long timeoutSeconds = 30;

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "-d":
                case "--depth":
                    if (i + 1 < args.length) {
                        maxDepth = Integer.parseInt(args[++i]);
                    }
                    break;
                case "-n":
                case "--count":
                    if (i + 1 < args.length) {
                        maxCount = Integer.parseInt(args[++i]);
                    }
                    break;
                case "-t":
                case "--timeout":
                    if (i + 1 < args.length) {
                        timeoutSeconds = Long.parseLong(args[++i]);
                    }
                    break;
                case "-h":
                case "--help":
                    return getHelp();
            }
        }

        return startTracing(classPattern, methodPattern, maxDepth, maxCount, timeoutSeconds, sink);
    }

    private String startTracing(String classPattern, String methodPattern,
                              int maxDepth, int maxCount, long timeoutSeconds, StreamSink sink) throws Exception {

        // Find matching classes
        Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();
        String targetClassName = null;

        for (Class<?> clazz : loadedClasses) {
            if (matchesPattern(clazz.getName(), classPattern)) {
                targetClassName = clazz.getName();
                break;
            }
        }

        if (targetClassName == null) {
            return "No loaded class matches pattern: " + classPattern;
        }

        String traceId = UUID.randomUUID().toString();
        BlockingQueue<TraceResult> resultQueue = new LinkedBlockingQueue<>(config.getTraceQueueCapacity());

        // Register the trace
        TraceInterceptor.registerTrace(traceId, resultQueue);

        // Create and register enhancer
        TraceEnhancer enhancer = new TraceEnhancer(targetClassName, methodPattern, null, traceId);
        transformer.addEnhancer(targetClassName, enhancer);

        // Retransform the class
        Class<?> targetClass = Class.forName(targetClassName);
        instrumentation.retransformClasses(targetClass);

        // Create trace session
        TraceSession session = new TraceSession(traceId, targetClassName, methodPattern, resultQueue, enhancer);
        activeSessions.put(traceId, session);

        StringBuilder result = new StringBuilder();
        result.append("Started tracing ").append(targetClassName).append(".").append(methodPattern).append("\n");
        result.append("Trace ID: ").append(traceId).append("\n");
        result.append("Max depth: ").append(maxDepth).append(", Max events: ").append(maxCount);
        result.append(", Timeout: ").append(timeoutSeconds).append("s\n");
        result.append("Press Ctrl+C to stop tracing\n\n");

        if (sink != null) {
            sink.send(result.toString().trim());
            result.setLength(0);
        }

        // Collect results
        int eventCount = 0;
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        try {
            while (eventCount < maxCount) {
                long remainingTime = timeoutMs - (System.currentTimeMillis() - startTime);
                if (remainingTime <= 0) {
                    appendOrSend(result, sink, "\nTrace timeout reached");
                    break;
                }

                TraceResult traceResult = resultQueue.poll(Math.min(remainingTime, 1000), TimeUnit.MILLISECONDS);
                if (traceResult != null) {
                    if (traceResult.getDepth() <= maxDepth) {
                        appendOrSend(result, sink, formatTraceResult(traceResult, ++eventCount));
                    }
                } else {
                    // Check if we should continue (no results for 1 second)
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        appendOrSend(result, sink, "\nTrace timeout reached");
                        break;
                    }
                }
            }

            if (eventCount >= maxCount) {
                appendOrSend(result, sink, "\nMaximum event count reached");
            }

        } finally {
            // Cleanup
            stopTrace(traceId);
        }

        String summary = "Trace completed. Total events: " + eventCount;
        if (sink != null) {
            sink.close(summary);
            return "";
        }
        result.append(summary);
        return result.toString();
    }

    private void stopTrace(String traceId) {
        TraceSession session = activeSessions.remove(traceId);
        if (session != null) {
            try {
                // Remove enhancer
                transformer.removeEnhancer(session.getClassName(), session.getEnhancer());

                // Retransform class back to original
                Class<?> targetClass = Class.forName(session.getClassName());
                instrumentation.retransformClasses(targetClass);

                // Unregister trace
                TraceInterceptor.unregisterTrace(traceId);

            } catch (Exception e) {
                System.err.println("Error stopping trace: " + e.getMessage());
            }
        }
    }

    private String formatTraceResult(TraceResult result, int eventNumber) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%3d] ", eventNumber));
        sb.append(String.format("%s ", java.time.LocalTime.now().toString()));
        sb.append(result.toString());
        return sb.toString();
    }

    private void appendOrSend(StringBuilder result, StreamSink sink, String text) {
        if (sink != null) {
            sink.send(text);
        } else {
            result.append(text).append("\n");
        }
    }

    private boolean matchesPattern(String className, String pattern) {
        if ("*".equals(pattern)) {
            return true;
        }

        String regex = pattern.replace("*", ".*");
        return className.matches(regex);
    }

    private String getHelp() {
        return "Trace command usage:\n" +
               "  trace <class-pattern> <method-pattern> [options]\n\n" +
               "Options:\n" +
               "  -d, --depth <num>     Maximum trace depth (default: 10)\n" +
               "  -n, --count <num>     Maximum number of events to capture (default: 100)\n" +
               "  -t, --timeout <sec>   Timeout in seconds (default: 30)\n" +
               "  -h, --help            Show this help\n\n" +
               "Examples:\n" +
               "  trace com.example.* execute*\n" +
               "  trace *Service* *method* -d 5 -n 50\n" +
               "  trace MyClass doWork -t 60\n";
    }

    @Override
    public String getDescription() {
        return "Trace method execution call chains with timing";
    }

    private static class TraceSession {
        private final String traceId;
        private final String className;
        private final String methodPattern;
        private final BlockingQueue<TraceResult> resultQueue;
        private final ClassEnhancer enhancer;

        public TraceSession(String traceId, String className, String methodPattern, BlockingQueue<TraceResult> resultQueue, ClassEnhancer enhancer) {
            this.traceId = traceId;
            this.className = className;
            this.methodPattern = methodPattern;
            this.resultQueue = resultQueue;
            this.enhancer = enhancer;
        }

        public String getTraceId() { return traceId; }
        public String getClassName() { return className; }
        public String getMethodPattern() { return methodPattern; }
        public BlockingQueue<TraceResult> getResultQueue() { return resultQueue; }
        public ClassEnhancer getEnhancer() { return enhancer; }
    }
}
