package com.javasleuth.agent;

import com.javasleuth.command.CommandProcessor;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

public class SleuthAgent {
    private static final AtomicBoolean ATTACHED = new AtomicBoolean(false);
    private static Instrumentation instrumentation;
    private static CommandProcessor commandProcessor;
    private static SleuthClassFileTransformer transformer;

    public static void agentmain(String agentArgs, Instrumentation inst) {
        if (!ATTACHED.compareAndSet(false, true)) {
            System.out.println("Java-Sleuth agent is already attached to this JVM");
            return;
        }

        instrumentation = inst;
        System.out.println("Java-Sleuth agent attached successfully");

        try {
            applyAgentArgs(agentArgs);

            // Initialize the class file transformer
            transformer = new SleuthClassFileTransformer();
            inst.addTransformer(transformer, true);

            commandProcessor = new CommandProcessor(inst, transformer);

            Thread commandThread = new Thread(() -> {
                commandProcessor.start();
            }, "sleuth-command-processor");

            commandThread.setDaemon(false);
            commandThread.start();

        } catch (Exception e) {
            System.err.println("Failed to start Java-Sleuth agent: " + e.getMessage());
            e.printStackTrace();
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

    public static void shutdown() {
        if (commandProcessor != null) {
            commandProcessor.shutdown();
        }
        if (transformer != null) {
            transformer.removeAllEnhancers();
            instrumentation.removeTransformer(transformer);
        }
        ATTACHED.set(false);
        System.out.println("Java-Sleuth agent shutdown");
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
