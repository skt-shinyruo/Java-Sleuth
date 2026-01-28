package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.command.StreamCommand;
import com.javasleuth.command.StreamSink;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.enhancement.ClassEnhancer;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.enhancement.WatchEnhancer;
import com.javasleuth.monitor.WatchInterceptor;
import com.javasleuth.data.WatchResult;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class WatchCommand implements StreamCommand {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ProductionConfig config;
    private final ConcurrentHashMap<String, WatchSession> activeSessions = new ConcurrentHashMap<>();

    public WatchCommand(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = ProductionConfig.getInstance();
    }

    @Override
    public String execute(String[] args) throws Exception {
        return runWatch(args, null);
    }

    @Override
    public void executeStream(String[] args, StreamSink sink) throws Exception {
        runWatch(args, sink);
    }

    private String runWatch(String[] args, StreamSink sink) throws Exception {
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
        boolean captureParams = true;
        boolean captureReturn = true;
        boolean captureException = true;
        int maxCount = 100;
        long timeoutSeconds = 30;

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
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
                case "--no-params":
                    captureParams = false;
                    break;
                case "--no-return":
                    captureReturn = false;
                    break;
                case "--no-exception":
                    captureException = false;
                    break;
                case "-h":
                case "--help":
                    return getHelp();
            }
        }

        return startWatching(classPattern, methodPattern, captureParams, captureReturn,
                           captureException, maxCount, timeoutSeconds, sink);
    }

    private String startWatching(String classPattern, String methodPattern,
                               boolean captureParams, boolean captureReturn, boolean captureException,
                               int maxCount, long timeoutSeconds, StreamSink sink) throws Exception {

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

        String watchId = UUID.randomUUID().toString();
        BlockingQueue<WatchResult> resultQueue = new LinkedBlockingQueue<>(config.getWatchQueueCapacity());

        // Register the watch
        WatchInterceptor.registerWatch(watchId, resultQueue);

        // Create and register enhancer
        WatchEnhancer enhancer = new WatchEnhancer(targetClassName, methodPattern, null,
                                                  captureParams, captureReturn, captureException, watchId);
        transformer.addEnhancer(targetClassName, enhancer);

        // Retransform the class
        Class<?> targetClass = Class.forName(targetClassName);
        instrumentation.retransformClasses(targetClass);

        // Create watch session
        WatchSession session = new WatchSession(watchId, targetClassName, methodPattern, resultQueue, enhancer);
        activeSessions.put(watchId, session);

        StringBuilder result = new StringBuilder();
        result.append("Started watching ").append(targetClassName).append(".").append(methodPattern).append("\n");
        result.append("Watch ID: ").append(watchId).append("\n");
        result.append("Capturing: ");
        if (captureParams) result.append("params ");
        if (captureReturn) result.append("return ");
        if (captureException) result.append("exception ");
        result.append("\n");
        result.append("Max events: ").append(maxCount).append(", Timeout: ").append(timeoutSeconds).append("s\n");
        result.append("Press Ctrl+C to stop watching\n\n");

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
                    appendOrSend(result, sink, "\nWatch timeout reached");
                    break;
                }

                WatchResult watchResult = resultQueue.poll(Math.min(remainingTime, 1000), TimeUnit.MILLISECONDS);
                if (watchResult != null) {
                    appendOrSend(result, sink, formatWatchResult(watchResult, ++eventCount));
                } else {
                    // Check if we should continue (no results for 1 second)
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        appendOrSend(result, sink, "\nWatch timeout reached");
                        break;
                    }
                }
            }

            if (eventCount >= maxCount) {
                appendOrSend(result, sink, "\nMaximum event count reached");
            }

        } finally {
            // Cleanup
            stopWatch(watchId);
        }

        String summary = "Watch completed. Total events: " + eventCount;
        if (sink != null) {
            sink.close(summary);
            return "";
        }
        result.append(summary);
        return result.toString();
    }

    private void stopWatch(String watchId) {
        WatchSession session = activeSessions.remove(watchId);
        if (session != null) {
            try {
                // Remove enhancer
                transformer.removeEnhancer(session.getClassName(), session.getEnhancer());

                // Retransform class back to original
                Class<?> targetClass = Class.forName(session.getClassName());
                instrumentation.retransformClasses(targetClass);

                // Unregister watch
                WatchInterceptor.unregisterWatch(watchId);

            } catch (Exception e) {
                System.err.println("Error stopping watch: " + e.getMessage());
            }
        }
    }

    private String formatWatchResult(WatchResult result, int eventNumber) {
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
        return "Watch command usage:\n" +
               "  watch <class-pattern> <method-pattern> [options]\n\n" +
               "Options:\n" +
               "  -n, --count <num>     Maximum number of events to capture (default: 100)\n" +
               "  -t, --timeout <sec>   Timeout in seconds (default: 30)\n" +
               "  --no-params          Don't capture method parameters\n" +
               "  --no-return          Don't capture return values\n" +
               "  --no-exception       Don't capture exceptions\n" +
               "  -h, --help           Show this help\n\n" +
               "Examples:\n" +
               "  watch com.example.* execute*\n" +
               "  watch *Service* *method* -n 50 -t 60\n" +
               "  watch MyClass doWork --no-params\n";
    }

    @Override
    public String getDescription() {
        return "Watch method execution with parameters, return values, and timing";
    }

    private static class WatchSession {
        private final String watchId;
        private final String className;
        private final String methodPattern;
        private final BlockingQueue<WatchResult> resultQueue;
        private final ClassEnhancer enhancer;

        public WatchSession(String watchId, String className, String methodPattern, BlockingQueue<WatchResult> resultQueue, ClassEnhancer enhancer) {
            this.watchId = watchId;
            this.className = className;
            this.methodPattern = methodPattern;
            this.resultQueue = resultQueue;
            this.enhancer = enhancer;
        }

        public String getWatchId() { return watchId; }
        public String getClassName() { return className; }
        public String getMethodPattern() { return methodPattern; }
        public BlockingQueue<WatchResult> getResultQueue() { return resultQueue; }
        public ClassEnhancer getEnhancer() { return enhancer; }
    }
}
