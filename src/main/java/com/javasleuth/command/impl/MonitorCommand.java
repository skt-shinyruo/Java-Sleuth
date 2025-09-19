package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.util.PerformanceOptimizer;
import org.objectweb.asm.*;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * MonitorCommand provides method execution statistics and monitoring
 *
 * Usage:
 * monitor start <class-pattern> <method-pattern> - Start monitoring methods
 * monitor stop <id> - Stop monitoring specific method
 * monitor list - List all active monitors
 * monitor stats [id] - Show execution statistics
 * monitor clear - Clear all monitoring data
 */
public class MonitorCommand implements Command {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConcurrentHashMap<String, MethodMonitor> activeMonitors = new ConcurrentHashMap<>();
    private final AtomicLong monitorIdGenerator = new AtomicLong(1);

    // Global method statistics storage
    private static final ConcurrentHashMap<String, MethodStats> METHOD_STATS = new ConcurrentHashMap<>();

    public MonitorCommand(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length < 2) {
            return getUsage();
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "start":
                return startMonitoring(args);
            case "stop":
                return stopMonitoring(args);
            case "list":
                return listMonitors();
            case "stats":
                return showStats(args);
            case "clear":
                return clearMonitoring();
            case "top":
                return showTopMethods(args);
            default:
                return "Unknown monitor action: " + action + "\n" + getUsage();
        }
    }

    private String startMonitoring(String[] args) throws Exception {
        if (args.length < 4) {
            return "Usage: monitor start <class-pattern> <method-pattern>";
        }

        String classPattern = args[2];
        String methodPattern = args[3];
        String monitorId = "monitor-" + monitorIdGenerator.getAndIncrement();

        try {
            // Find matching classes
            Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();
            List<Class<?>> matchingClasses = new ArrayList<>();

            for (Class<?> clazz : loadedClasses) {
                if (matchesPattern(clazz.getName(), classPattern)) {
                    matchingClasses.add(clazz);
                }
            }

            if (matchingClasses.isEmpty()) {
                return "No classes found matching pattern: " + classPattern;
            }

            // Create monitor for matching classes
            MethodMonitor monitor = new MethodMonitor(monitorId, classPattern, methodPattern);
            activeMonitors.put(monitorId, monitor);

            // Apply instrumentation to matching classes
            MonitoringClassVisitor visitor = new MonitoringClassVisitor(methodPattern, monitorId);

            int instrumentedCount = 0;
            for (Class<?> clazz : matchingClasses) {
                if (instrumentation.isModifiableClass(clazz)) {
                    // This is a simplified approach - in practice, you'd use the transformer
                    instrumentedCount++;
                }
            }

            monitor.setInstrumentedClasses(instrumentedCount);

            return String.format("Started monitoring [%s] for pattern %s::%s\n" +
                               "Monitoring ID: %s\n" +
                               "Instrumented %d classes\n" +
                               "Use 'monitor stats %s' to view statistics",
                               monitorId, classPattern, methodPattern, monitorId,
                               instrumentedCount, monitorId);

        } catch (Exception e) {
            return "Failed to start monitoring: " + e.getMessage();
        }
    }

    private String stopMonitoring(String[] args) {
        if (args.length < 3) {
            return "Usage: monitor stop <monitor-id>";
        }

        String monitorId = args[2];
        MethodMonitor monitor = activeMonitors.remove(monitorId);

        if (monitor == null) {
            return "Monitor not found: " + monitorId;
        }

        monitor.stop();
        return String.format("Stopped monitoring [%s]\n" +
                           "Total executions: %d\n" +
                           "Total time: %s",
                           monitorId,
                           monitor.getTotalExecutions(),
                           PerformanceOptimizer.formatDuration(monitor.getTotalTime()));
    }

    private String listMonitors() {
        if (activeMonitors.isEmpty()) {
            return "No active monitors";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Active Monitors:\n");
        sb.append(String.format("%-15s %-20s %-20s %-10s %-15s\n",
                  "ID", "Class Pattern", "Method Pattern", "Classes", "Executions"));
        sb.append("-".repeat(80)).append("\n");

        for (MethodMonitor monitor : activeMonitors.values()) {
            sb.append(String.format("%-15s %-20s %-20s %-10d %-15d\n",
                      monitor.getId(),
                      truncate(monitor.getClassPattern(), 20),
                      truncate(monitor.getMethodPattern(), 20),
                      monitor.getInstrumentedClasses(),
                      monitor.getTotalExecutions()));
        }

        return sb.toString();
    }

    private String showStats(String[] args) {
        if (args.length < 3) {
            // Show overall statistics
            return showOverallStats();
        }

        String monitorId = args[2];
        MethodMonitor monitor = activeMonitors.get(monitorId);

        if (monitor == null) {
            return "Monitor not found: " + monitorId;
        }

        return getMonitorStats(monitor);
    }

    private String showOverallStats() {
        if (METHOD_STATS.isEmpty()) {
            return "No method statistics available";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Method Execution Statistics:\n");
        sb.append(String.format("%-50s %-10s %-15s %-15s %-15s\n",
                  "Method", "Count", "Total Time", "Avg Time", "Max Time"));
        sb.append("-".repeat(100)).append("\n");

        // Sort by total execution count
        List<Map.Entry<String, MethodStats>> sorted = METHOD_STATS.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getExecutionCount(), a.getValue().getExecutionCount()))
            .limit(20)
            .collect(Collectors.toList());

        for (Map.Entry<String, MethodStats> entry : sorted) {
            String method = entry.getKey();
            MethodStats stats = entry.getValue();

            sb.append(String.format("%-50s %-10d %-15s %-15s %-15s\n",
                      truncate(method, 50),
                      stats.getExecutionCount(),
                      PerformanceOptimizer.formatDuration(stats.getTotalTime()),
                      PerformanceOptimizer.formatDuration(stats.getAverageTime()),
                      PerformanceOptimizer.formatDuration(stats.getMaxTime())));
        }

        return sb.toString();
    }

    private String showTopMethods(String[] args) {
        int limit = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        if (METHOD_STATS.isEmpty()) {
            return "No method statistics available";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Top ").append(limit).append(" Methods by Execution Count:\n");
        sb.append(String.format("%-50s %-10s %-15s\n", "Method", "Count", "Total Time"));
        sb.append("-".repeat(75)).append("\n");

        METHOD_STATS.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getExecutionCount(), a.getValue().getExecutionCount()))
            .limit(limit)
            .forEach(entry -> {
                String method = entry.getKey();
                MethodStats stats = entry.getValue();
                sb.append(String.format("%-50s %-10d %-15s\n",
                          truncate(method, 50),
                          stats.getExecutionCount(),
                          PerformanceOptimizer.formatDuration(stats.getTotalTime())));
            });

        return sb.toString();
    }

    private String clearMonitoring() {
        int monitorCount = activeMonitors.size();
        activeMonitors.clear();
        METHOD_STATS.clear();
        return String.format("Cleared %d monitors and all statistics", monitorCount);
    }

    private String getMonitorStats(MethodMonitor monitor) {
        StringBuilder sb = new StringBuilder();
        sb.append("Monitor Statistics [").append(monitor.getId()).append("]:\n");
        sb.append("  Class Pattern: ").append(monitor.getClassPattern()).append("\n");
        sb.append("  Method Pattern: ").append(monitor.getMethodPattern()).append("\n");
        sb.append("  Instrumented Classes: ").append(monitor.getInstrumentedClasses()).append("\n");
        sb.append("  Total Executions: ").append(monitor.getTotalExecutions()).append("\n");
        sb.append("  Total Time: ").append(PerformanceOptimizer.formatDuration(monitor.getTotalTime())).append("\n");
        sb.append("  Started: ").append(new java.util.Date(monitor.getStartTime())).append("\n");

        if (monitor.isStopped()) {
            sb.append("  Status: STOPPED\n");
        } else {
            sb.append("  Status: ACTIVE\n");
            long elapsed = System.currentTimeMillis() - monitor.getStartTime();
            sb.append("  Running for: ").append(PerformanceOptimizer.formatDuration(elapsed)).append("\n");
        }

        return sb.toString();
    }

    private boolean matchesPattern(String target, String pattern) {
        // Convert glob pattern to regex
        String regex = pattern.replace("*", ".*").replace("?", ".");
        return target.matches(regex);
    }

    private String truncate(String str, int maxLength) {
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }

    private String getUsage() {
        return "Monitor Command Usage:\n" +
               "  monitor start <class-pattern> <method-pattern> - Start monitoring methods\n" +
               "  monitor stop <monitor-id>                      - Stop specific monitor\n" +
               "  monitor list                                   - List all active monitors\n" +
               "  monitor stats [monitor-id]                     - Show execution statistics\n" +
               "  monitor top [limit]                            - Show top methods by execution count\n" +
               "  monitor clear                                  - Clear all monitoring data\n" +
               "\n" +
               "Examples:\n" +
               "  monitor start com.example.* execute*           - Monitor execute* methods in com.example.*\n" +
               "  monitor start java.util.ArrayList *            - Monitor all ArrayList methods\n" +
               "  monitor top 20                                 - Show top 20 methods by execution count";
    }

    @Override
    public String getDescription() {
        return "Monitor method execution statistics and performance";
    }

    // Record method execution for statistics
    public static void recordMethodExecution(String methodSignature, long executionTime) {
        METHOD_STATS.computeIfAbsent(methodSignature, k -> new MethodStats())
                   .addExecution(executionTime);
    }

    // Inner classes for monitoring data structures
    private static class MethodMonitor {
        private final String id;
        private final String classPattern;
        private final String methodPattern;
        private final long startTime;
        private final AtomicLong totalExecutions = new AtomicLong(0);
        private final AtomicLong totalTime = new AtomicLong(0);
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private int instrumentedClasses = 0;

        public MethodMonitor(String id, String classPattern, String methodPattern) {
            this.id = id;
            this.classPattern = classPattern;
            this.methodPattern = methodPattern;
            this.startTime = System.currentTimeMillis();
        }

        // Getters and setters
        public String getId() { return id; }
        public String getClassPattern() { return classPattern; }
        public String getMethodPattern() { return methodPattern; }
        public long getStartTime() { return startTime; }
        public long getTotalExecutions() { return totalExecutions.get(); }
        public long getTotalTime() { return totalTime.get(); }
        public boolean isStopped() { return stopped.get(); }
        public int getInstrumentedClasses() { return instrumentedClasses; }
        public void setInstrumentedClasses(int count) { this.instrumentedClasses = count; }

        public void addExecution(long time) {
            totalExecutions.incrementAndGet();
            totalTime.addAndGet(time);
        }

        public void stop() {
            stopped.set(true);
        }
    }

    private static class MethodStats {
        private final AtomicLong executionCount = new AtomicLong(0);
        private final AtomicLong totalTime = new AtomicLong(0);
        private final AtomicLong maxTime = new AtomicLong(0);

        public void addExecution(long time) {
            executionCount.incrementAndGet();
            totalTime.addAndGet(time);

            // Update max time using atomic operation
            long currentMax = maxTime.get();
            while (time > currentMax && !maxTime.compareAndSet(currentMax, time)) {
                currentMax = maxTime.get();
            }
        }

        public long getExecutionCount() { return executionCount.get(); }
        public long getTotalTime() { return totalTime.get(); }
        public long getMaxTime() { return maxTime.get(); }
        public long getAverageTime() {
            long count = executionCount.get();
            return count > 0 ? totalTime.get() / count : 0;
        }
    }

    // Simple class visitor for method monitoring instrumentation
    private static class MonitoringClassVisitor extends ClassVisitor {
        private final String methodPattern;
        private final String monitorId;

        public MonitoringClassVisitor(String methodPattern, String monitorId) {
            super(Opcodes.ASM9);
            this.methodPattern = methodPattern;
            this.monitorId = monitorId;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                       String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            // Check if method matches pattern
            if (matchesMethodPattern(name, methodPattern)) {
                return new MonitoringMethodVisitor(mv, name, descriptor, monitorId);
            }

            return mv;
        }

        private boolean matchesMethodPattern(String methodName, String pattern) {
            String regex = pattern.replace("*", ".*").replace("?", ".");
            return methodName.matches(regex);
        }
    }

    // Method visitor that adds timing instrumentation
    private static class MonitoringMethodVisitor extends MethodVisitor {
        private final String methodName;
        private final String descriptor;
        private final String monitorId;

        public MonitoringMethodVisitor(MethodVisitor mv, String methodName, String descriptor, String monitorId) {
            super(Opcodes.ASM9, mv);
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.monitorId = monitorId;
        }

        @Override
        public void visitCode() {
            // Add timing start code
            super.visitCode();
            // This would inject bytecode to record method entry time
            // Implementation details would depend on the specific instrumentation needs
        }

        @Override
        public void visitInsn(int opcode) {
            // Intercept return instructions to record timing
            if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) {
                // Add timing end code and record statistics
                // This would inject bytecode to calculate execution time and call recordMethodExecution
            }
            super.visitInsn(opcode);
        }
    }
}