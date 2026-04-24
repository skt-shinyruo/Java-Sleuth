package com.javasleuth.core.agent.runtime;

import com.javasleuth.core.command.CommandProcessor;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import java.lang.instrument.Instrumentation;

/**
 * Sleuth agent runtime container (per attach lifecycle).
 *
 * <p>Goal: compress runtime state into a single object and provide a unified, idempotent close() path.
 */
public final class SleuthAgentRuntime implements AutoCloseable {
    private final AttachSessionContext sessionContext;

    private SleuthAgentRuntime(AttachSessionContext sessionContext) {
        if (sessionContext == null) {
            throw new IllegalArgumentException("sessionContext is required");
        }
        this.sessionContext = sessionContext;
    }

    public static SleuthAgentRuntime start(Instrumentation inst, Runnable shutdownHook) {
        SleuthAgentRuntime runtime = create(inst, shutdownHook);
        runtime.startCommandProcessorAsync();
        return runtime;
    }

    /**
     * Build a runtime without starting the command processor thread.
     *
     * <p>Intended for tests and for scenarios where caller wants to control when to start.</p>
     */
    public static SleuthAgentRuntime create(Instrumentation inst, Runnable shutdownHook) {
        if (inst == null) {
            throw new IllegalArgumentException("instrumentation is required");
        }
        return new SleuthAgentRuntime(AttachSessionContext.create(inst, shutdownHook));
    }

    public void startCommandProcessorAsync() {
        sessionContext.startCommandProcessorAsync();
    }

    public Instrumentation getInstrumentation() {
        return sessionContext.getInstrumentation();
    }

    public SleuthClassFileTransformer getTransformer() {
        return sessionContext.getTransformer();
    }

    public CommandProcessor getCommandProcessor() {
        return sessionContext.getCommandProcessor();
    }

    @Override
    public void close() {
        sessionContext.close();
    }
}
