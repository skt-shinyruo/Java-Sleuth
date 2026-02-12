package com.javasleuth.agent.core;

import com.javasleuth.command.CommandProcessor;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.util.SleuthLogger;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

public class SleuthAgentCore {
    private static final AtomicBoolean ATTACHED = new AtomicBoolean(false);
    private static Instrumentation instrumentation;
    private static CommandProcessor commandProcessor;
    private static SleuthClassFileTransformer transformer;

    public static void agentmain(String agentArgs, Instrumentation inst) {
        if (!ATTACHED.compareAndSet(false, true)) {
            SleuthLogger.warn("Java-Sleuth agent is already attached to this JVM");
            return;
        }

        instrumentation = inst;
        SleuthLogger.info("Java-Sleuth agent attached successfully");

        try {
            applyAgentArgs(agentArgs);

            // Initialize the class file transformer
            transformer = new SleuthClassFileTransformer();
            inst.addTransformer(transformer, true);

            commandProcessor = new CommandProcessor(inst, transformer, SleuthAgentCore::shutdown);

            Thread commandThread = new Thread(() -> {
                commandProcessor.start();
            }, "sleuth-command-processor");

            // Do not block JVM exit when only daemon threads remain.
            commandThread.setDaemon(true);
            commandThread.start();

        } catch (Exception e) {
            SleuthLogger.error("Failed to start Java-Sleuth agent: " + e.getMessage(), e);
        }
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        agentmain(agentArgs, inst);
    }

    public static SleuthClassFileTransformer getTransformer() {
        return transformer;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static boolean isAttached() {
        return ATTACHED.get();
    }

    /**
     * Best-effort safe shutdown.
     *
     * <p>Goal: behave like a full reset rollback before removing the transformer.
     * This minimizes residual instrumentation state if shutdown happens mid-command.
     */
    public static void shutdown() {
        Instrumentation inst = instrumentation;
        SleuthClassFileTransformer tx = transformer;

        // Snapshot enhanced class names before we start removing state.
        java.util.Set<String> enhanced = java.util.Collections.emptySet();
        if (tx != null) {
            try {
                enhanced = new java.util.HashSet<>(tx.getEnhancedClassNames());
            } catch (Exception ignore) {
                enhanced = java.util.Collections.emptySet();
            }
        }

        // 1) Stop command processor (stops networking / threads) but keep transformer in place for rollback.
        if (commandProcessor != null) {
            try {
                commandProcessor.shutdown();
            } catch (Exception ignore) {
                // best-effort
            }
        }

        // 2) Stop jobs / clear interceptor sessions.
        try {
            com.javasleuth.command.JobManager.getInstance().stopAll("shutdown");
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            com.javasleuth.monitor.WatchInterceptor.unregisterAllWatches();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            com.javasleuth.monitor.TraceInterceptor.unregisterAllTraces();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            com.javasleuth.monitor.MonitorInterceptor.unregisterAllMonitors();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            com.javasleuth.monitor.TtInterceptor.unregisterAll();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            com.javasleuth.monitor.StackInterceptor.unregisterAll();
        } catch (Exception ignore) {
            // best-effort
        }

        // 3) Remove enhancers.
        if (tx != null) {
            try {
                tx.removeAllEnhancers();
            } catch (Exception ignore) {
                // best-effort
            }
        }

        // 4) Retransform classes we previously enhanced, best-effort.
        if (inst != null && !enhanced.isEmpty()) {
            try {
                Class<?>[] loaded = inst.getAllLoadedClasses();
                for (Class<?> c : loaded) {
                    if (c == null) {
                        continue;
                    }
                    if (!enhanced.contains(c.getName())) {
                        continue;
                    }
                    if (!inst.isModifiableClass(c)) {
                        continue;
                    }
                    try {
                        inst.retransformClasses(c);
                    } catch (Exception ignore) {
                        // best-effort per-class
                    }
                }
            } catch (Exception ignore) {
                // best-effort
            }
        }

        // 5) Unregister transformer.
        if (tx != null && inst != null) {
            try {
                inst.removeTransformer(tx);
            } catch (Exception e) {
                SleuthLogger.warn("Java-Sleuth: Failed to remove transformer: " + e.getMessage(), e);
            }
        }

        ATTACHED.set(false);
        SleuthLogger.info("Java-Sleuth agent shutdown");
    }

    private static void applyAgentArgs(String agentArgs) {
        if (agentArgs == null || agentArgs.trim().isEmpty()) {
            return;
        }

        String[] pairs = agentArgs.split(";");
        for (String pair : pairs) {
            if (pair == null) {
                continue;
            }
            String trimmed = pair.trim();
            if (trimmed.isEmpty() || !trimmed.contains("=")) {
                continue;
            }

            String[] kv = trimmed.split("=", 2);
            String key = kv[0].trim();
            String value = kv.length > 1 ? kv[1].trim() : "";
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }

            if ("configFile".equalsIgnoreCase(key)) {
                System.setProperty("sleuth.config.file", value);
            } else if (key.startsWith("sleuth.")) {
                System.setProperty(key, value);
            } else {
                System.setProperty("sleuth." + key, value);
            }
        }
    }
}
