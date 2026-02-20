package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.command.JobManager;
import com.javasleuth.agent.runtime.AgentGlobalState;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.vmtool.VmToolSessionRegistry;
import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Set;

/**
 * Reset all active enhancements and related sessions.
 *
 * <p>Simplified behavior:
 * - Stop all background jobs (best-effort)
 * - Clear all interceptors
 * - Remove all enhancers from transformer
 * - Retransform previously enhanced classes to restore original bytecode
 */
public class ResetCommand implements Command {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final JobManager jobManager;
    private final VmToolSessionRegistry vmToolSessionRegistry;

    public ResetCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        JobManager jobManager,
        VmToolSessionRegistry vmToolSessionRegistry
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        if (jobManager == null) {
            throw new IllegalArgumentException("jobManager");
        }
        if (vmToolSessionRegistry == null) {
            throw new IllegalArgumentException("vmToolSessionRegistry");
        }
        this.jobManager = jobManager;
        this.vmToolSessionRegistry = vmToolSessionRegistry;
    }

    @Override
    public String execute(String[] args) {
        int stoppedJobs = jobManager.stopAll("reset");

        Set<String> enhanced = new HashSet<>(transformer.getEnhancedClassNames());

        // Clear vmtool sessions first (remove its enhancers best-effort).
        try {
            vmToolSessionRegistry.stopAll(instrumentation, transformer, "reset");
        } catch (Exception ignore) {
            // best-effort
        }

        // Clear interceptor sessions first to reduce further event publishing.
        AgentGlobalState.resetInterceptorsBestEffort();

        transformer.removeAllEnhancers();

        int retransformCount = 0;
        int skipped = 0;
        try {
            Class<?>[] loaded = instrumentation.getAllLoadedClasses();
            for (Class<?> c : loaded) {
                if (c == null) {
                    continue;
                }
                if (!enhanced.contains(c.getName())) {
                    continue;
                }
                if (!instrumentation.isModifiableClass(c)) {
                    skipped++;
                    continue;
                }
                try {
                    instrumentation.retransformClasses(c);
                    retransformCount++;
                } catch (Exception ex) {
                    skipped++;
                }
            }
        } catch (Exception e) {
            // Ignore: reset is best-effort.
        }

        return "Reset done. jobsStopped=" + stoppedJobs +
            ", enhancedClasses=" + enhanced.size() +
            ", retransformed=" + retransformCount +
            ", skipped=" + skipped;
    }

    @Override
    public String getDescription() {
        return "Reset all active enhancements (clear sessions, remove enhancers, retransform classes)";
    }
}
