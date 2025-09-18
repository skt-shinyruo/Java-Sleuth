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
}