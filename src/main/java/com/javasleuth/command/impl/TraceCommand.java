package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
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

public class TraceCommand implements Command {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConcurrentHashMap<String, TraceSession> activeSessions = new ConcurrentHashMap<>();

    public TraceCommand(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length < 3) {
            return getHelp();
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

        return startTracing(classPattern, methodPattern, maxDepth, maxCount, timeoutSeconds);
    }

    private String startTracing(String classPattern, String methodPattern,
                              int maxDepth, int maxCount, long timeoutSeconds) throws Exception {

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
        BlockingQueue<TraceResult> resultQueue = new LinkedBlockingQueue<>();

        // Register the trace
        TraceInterceptor.registerTrace(traceId, resultQueue);

        // Create and register enhancer
        TraceEnhancer enhancer = new TraceEnhancer(targetClassName, methodPattern, null, traceId);
        transformer.addEnhancer(targetClassName, enhancer);

        // Retransform the class
        Class<?> targetClass = Class.forName(targetClassName);
        instrumentation.retransformClasses(targetClass);

        // Create trace session
        TraceSession session = new TraceSession(traceId, targetClassName, methodPattern, resultQueue);
        activeSessions.put(traceId, session);

        StringBuilder result = new StringBuilder();
        result.append("Started tracing ").append(targetClassName).append(".").append(methodPattern).append("\n");
        result.append("Trace ID: ").append(traceId).append("\n");
        result.append("Max depth: ").append(maxDepth).append(", Max events: ").append(maxCount);
        result.append(", Timeout: ").append(timeoutSeconds).append("s\n");
        result.append("Press Ctrl+C to stop tracing\n\n");

        // Collect results
        int eventCount = 0;
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        try {
            while (eventCount < maxCount) {
                long remainingTime = timeoutMs - (System.currentTimeMillis() - startTime);
                if (remainingTime <= 0) {
                    result.append("\nTrace timeout reached\n");
                    break;
                }

                TraceResult traceResult = resultQueue.poll(Math.min(remainingTime, 1000), TimeUnit.MILLISECONDS);
                if (traceResult != null) {
                    if (traceResult.getDepth() <= maxDepth) {
                        result.append(formatTraceResult(traceResult, ++eventCount)).append("\n");
                    }
                } else {
                    // Check if we should continue (no results for 1 second)
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        result.append("\nTrace timeout reached\n");
                        break;
                    }
                }
            }

            if (eventCount >= maxCount) {
                result.append("\nMaximum event count reached\n");
            }

        } finally {
            // Cleanup
            stopTrace(traceId);
        }

        result.append("Trace completed. Total events: ").append(eventCount);
        return result.toString();
    }

    private void stopTrace(String traceId) {
        TraceSession session = activeSessions.remove(traceId);
        if (session != null) {
            try {
                // Remove enhancer
                transformer.removeEnhancer(session.getClassName());

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

        public TraceSession(String traceId, String className, String methodPattern, BlockingQueue<TraceResult> resultQueue) {
            this.traceId = traceId;
            this.className = className;
            this.methodPattern = methodPattern;
            this.resultQueue = resultQueue;
        }

        public String getTraceId() { return traceId; }
        public String getClassName() { return className; }
        public String getMethodPattern() { return methodPattern; }
        public BlockingQueue<TraceResult> getResultQueue() { return resultQueue; }
    }
}