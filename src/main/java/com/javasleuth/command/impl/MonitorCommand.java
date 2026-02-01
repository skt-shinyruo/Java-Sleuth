package com.javasleuth.command.impl;

import com.javasleuth.command.JobManager;
import com.javasleuth.command.StreamCommand;
import com.javasleuth.command.StreamSink;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.enhancement.ClassEnhancer;
import com.javasleuth.enhancement.MonitorEnhancer;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.monitor.MonitorInterceptor;
import com.javasleuth.util.WildcardMatcher;
import com.javasleuth.util.StringUtils;
import java.lang.instrument.Instrumentation;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Method monitor (simplified Arthas-like).
 *
 * Usage:
 *   monitor <class-pattern> <method-pattern> [-i <ms>] [-n <rounds>] [--limit <classes>] [--bg]
 */
public class MonitorCommand implements StreamCommand {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ProductionConfig config;
    private final ConcurrentHashMap<String, MonitorSession> activeSessions = new ConcurrentHashMap<>();

    public MonitorCommand(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = ProductionConfig.getInstance();
    }

    @Override
    public String execute(String[] args) throws Exception {
        return runMonitor(args, null);
    }

    @Override
    public void executeStream(String[] args, StreamSink sink) throws Exception {
        runMonitor(args, sink);
    }

    private String runMonitor(String[] args, StreamSink sink) throws Exception {
        if (args == null || args.length < 3) {
            String help = getHelp();
            if (sink != null) {
                sink.error(help);
                return "";
            }
            return help;
        }

        boolean background = false;
        String classPattern = args[1];
        String methodPattern = args[2];

        long intervalMs = 5000;
        int rounds = 10;
        int classLimit = 50;

        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if ("-i".equals(a) || "--interval".equals(a)) {
                if (i + 1 < args.length) {
                    intervalMs = parseLong(args[++i], 5000);
                }
            } else if ("-n".equals(a) || "--count".equals(a)) {
                if (i + 1 < args.length) {
                    rounds = parseInt(args[++i], 10);
                }
            } else if ("--limit".equals(a)) {
                if (i + 1 < args.length) {
                    classLimit = parseInt(args[++i], 50);
                }
            } else if ("--bg".equals(a)) {
                background = true;
            } else if ("-h".equals(a) || "--help".equals(a)) {
                return getHelp();
            }
        }

        if (background) {
            String[] jobArgs = removeFlag(args, "--bg");
            String commandLine = String.join(" ", jobArgs);
            String jobId = JobManager.getInstance().submitStreamJob(
                "monitor",
                commandLine,
                jobSink -> runMonitor(jobArgs, jobSink)
            );
            String msg = "Started monitor in background. Job ID: " + jobId + " (use: jobs tail " + jobId + ")";
            if (sink != null) {
                sink.send(msg);
                sink.close("job started");
                return "";
            }
            return msg;
        }

        return startMonitoring(classPattern, methodPattern, intervalMs, rounds, classLimit, sink);
    }

    private String startMonitoring(String classPattern, String methodPattern,
                                   long intervalMs, int rounds, int classLimit,
                                   StreamSink sink) throws Exception {
        List<Class<?>> matches = new ArrayList<>();
        for (Class<?> c : instrumentation.getAllLoadedClasses()) {
            if (c == null) {
                continue;
            }
            if (!WildcardMatcher.matches(c.getName(), classPattern)) {
                continue;
            }
            if (!instrumentation.isModifiableClass(c)) {
                continue;
            }
            matches.add(c);
            if (matches.size() >= classLimit) {
                break;
            }
        }

        if (matches.isEmpty()) {
            return "No modifiable loaded class matches pattern: " + classPattern;
        }

        String monitorId = UUID.randomUUID().toString();
        boolean interceptorRegistered = false;
        List<MonitorSession.EnhancedClass> enhanced = new ArrayList<>();
        try {
            MonitorInterceptor.registerMonitor(monitorId);
            interceptorRegistered = true;
            MonitorInterceptor.clear(monitorId);

            for (Class<?> c : matches) {
                MonitorEnhancer enhancer = new MonitorEnhancer(c.getName(), methodPattern, null, monitorId);
                transformer.addEnhancer(c.getName(), enhancer);
                try {
                    instrumentation.retransformClasses(c);
                } catch (Exception e) {
                    // Roll back this class registration and then abort.
                    try {
                        transformer.removeEnhancer(c.getName(), enhancer);
                    } catch (Exception ignore) {
                        // ignore
                    }
                    try {
                        instrumentation.retransformClasses(c);
                    } catch (Exception ignore) {
                        // ignore
                    }
                    throw e;
                }
                enhanced.add(new MonitorSession.EnhancedClass(c, enhancer));
            }
        } catch (Exception e) {
            // Rollback any partial state best-effort.
            for (MonitorSession.EnhancedClass ec : enhanced) {
                try {
                    transformer.removeEnhancer(ec.className, ec.enhancer);
                } catch (Exception ignore) {
                    // ignore
                }
                try {
                    instrumentation.retransformClasses(ec.clazz);
                } catch (Exception ignore) {
                    // ignore
                }
            }
            if (interceptorRegistered) {
                try {
                    MonitorInterceptor.unregisterMonitor(monitorId);
                } catch (Exception ignore) {
                    // ignore
                }
            }
            throw e;
        }

        MonitorSession session = new MonitorSession(monitorId, classPattern, methodPattern, enhanced);
        activeSessions.put(monitorId, session);

        StringBuilder banner = new StringBuilder();
        banner.append("Started monitor ").append(classPattern).append(".").append(methodPattern).append("\n");
        banner.append("Monitor ID: ").append(monitorId).append("\n");
        banner.append("Classes instrumented: ").append(enhanced.size()).append("\n");
        banner.append("Interval: ").append(intervalMs).append("ms, Rounds: ").append(rounds).append("\n");

        if (sink != null) {
            sink.send(banner.toString().trim());
        }

        StringBuilder out = new StringBuilder();
        int done = 0;
        try {
            for (int i = 0; i < rounds; i++) {
                try {
                    Thread.sleep(Math.max(1, intervalMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    appendOrSend(out, sink, "\nMonitor interrupted");
                    break;
                }

                Map<String, MonitorInterceptor.MethodStatsSnapshot> snap = MonitorInterceptor.snapshot(monitorId);
                appendOrSend(out, sink, formatSnapshot(snap, i + 1, rounds));
                MonitorInterceptor.clear(monitorId);
                done++;
            }
        } finally {
            stopMonitor(monitorId);
        }

        String summary = "Monitor completed. rounds=" + done;
        if (sink != null) {
            sink.close(summary);
            return "";
        }
        out.append(summary);
        return out.toString();
    }

    private void stopMonitor(String monitorId) {
        MonitorSession session = activeSessions.remove(monitorId);
        if (session == null) {
            MonitorInterceptor.unregisterMonitor(monitorId);
            return;
        }

        try {
            for (MonitorSession.EnhancedClass ec : session.enhancedClasses) {
                transformer.removeEnhancer(ec.className, ec.enhancer);
                try {
                    instrumentation.retransformClasses(ec.clazz);
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        } finally {
            MonitorInterceptor.unregisterMonitor(monitorId);
        }
    }

    private String formatSnapshot(Map<String, MonitorInterceptor.MethodStatsSnapshot> snap, int round, int rounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Monitor Stats === ").append(LocalTime.now()).append(" (").append(round).append("/").append(rounds).append(")\n");
        if (snap == null || snap.isEmpty()) {
            sb.append("(no calls)\n");
            return sb.toString().trim();
        }

        List<Map.Entry<String, MonitorInterceptor.MethodStatsSnapshot>> rows = new ArrayList<>(snap.entrySet());
        rows.sort(Comparator.comparingLong((Map.Entry<String, MonitorInterceptor.MethodStatsSnapshot> e) -> e.getValue().getTotalNanos()).reversed());

        sb.append(String.format("%-60s %8s %8s %10s %10s %10s\n", "METHOD", "COUNT", "EX", "AVG(ms)", "MAX(ms)", "MIN(ms)"));
        sb.append(StringUtils.repeat('=', 120)).append("\n");

        int shown = 0;
        for (Map.Entry<String, MonitorInterceptor.MethodStatsSnapshot> e : rows) {
            if (shown >= 20) {
                break;
            }
            MonitorInterceptor.MethodStatsSnapshot s = e.getValue();
            long count = s.getCount();
            double avgMs = count <= 0 ? 0.0 : (s.getTotalNanos() / 1_000_000.0) / count;
            sb.append(String.format("%-60s %8d %8d %10.2f %10.2f %10.2f\n",
                truncate(e.getKey(), 60),
                count,
                s.getExceptionCount(),
                avgMs,
                s.getMaxNanos() / 1_000_000.0,
                s.getMinNanos() / 1_000_000.0
            ));
            shown++;
        }
        return sb.toString().trim();
    }

    private void appendOrSend(StringBuilder buf, StreamSink sink, String text) {
        if (sink != null) {
            sink.send(text);
        } else {
            buf.append(text).append("\n");
        }
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

    private long parseLong(String raw, long def) {
        if (raw == null) {
            return def;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    private String getHelp() {
        return "Monitor command usage:\n" +
            "  monitor <class-pattern> <method-pattern> [options]\n\n" +
            "Options:\n" +
            "  -i, --interval <ms>  Sampling interval in ms (default: 5000)\n" +
            "  -n, --count <num>    Number of rounds (default: 10)\n" +
            "  --limit <num>        Max classes to instrument (default: 50)\n" +
            "  --bg                 Run in background (use jobs tail/stop)\n" +
            "  -h, --help           Show this help\n";
    }

    @Override
    public String getDescription() {
        return "Monitor method statistics periodically (simplified)";
    }

    private static String[] removeFlag(String[] args, String flag) {
        if (args == null || args.length == 0 || flag == null || flag.isEmpty()) {
            return args;
        }
        List<String> out = new ArrayList<>();
        for (String a : args) {
            if (a == null) {
                continue;
            }
            if (flag.equals(a)) {
                continue;
            }
            out.add(a);
        }
        return out.toArray(new String[0]);
    }

    private static final class MonitorSession {
        private final String id;
        private final String classPattern;
        private final String methodPattern;
        private final List<EnhancedClass> enhancedClasses;

        private MonitorSession(String id, String classPattern, String methodPattern, List<EnhancedClass> enhancedClasses) {
            this.id = id;
            this.classPattern = classPattern;
            this.methodPattern = methodPattern;
            this.enhancedClasses = enhancedClasses;
        }

        private static final class EnhancedClass {
            private final Class<?> clazz;
            private final String className;
            private final ClassEnhancer enhancer;

            private EnhancedClass(Class<?> clazz, ClassEnhancer enhancer) {
                this.clazz = clazz;
                this.className = clazz != null ? clazz.getName() : "";
                this.enhancer = enhancer;
            }
        }
    }
}
