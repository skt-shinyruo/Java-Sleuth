package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.agent.runtime.AgentGlobalState;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionCloseSummary;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import java.lang.instrument.Instrumentation;
import java.util.Set;

/**
 * Reset all active enhancements and related sessions.
 *
 * <p>Simplified behavior:
 * - Stop all background jobs (best-effort)
 * - Clear compatibility-only bootstrap interceptor state
 * - Remove all enhancers from transformer
 * - Retransform previously enhanced classes to restore original bytecode
 */
public class ResetCommand implements Command {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final JobManager jobManager;
    private final VmToolSessionRegistry vmToolSessionRegistry;
    private final EnhancementSessionRegistry enhancementSessionRegistry;

    public ResetCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        JobManager jobManager,
        VmToolSessionRegistry vmToolSessionRegistry
    ) {
        this(instrumentation, transformer, jobManager, vmToolSessionRegistry, null);
    }

    public ResetCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        JobManager jobManager,
        VmToolSessionRegistry vmToolSessionRegistry,
        EnhancementSessionRegistry enhancementSessionRegistry
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
        this.enhancementSessionRegistry = enhancementSessionRegistry;
    }

    @Override
    public String execute(String[] args) {
        int stoppedJobs = jobManager.stopAll("reset");

        Set<SleuthClassFileTransformer.EnhancedClassRef> enhanced = transformer.getEnhancedClassRefs();

        int enhancementSessions = 0;
        int enhancementClosed = 0;
        int enhancementFailed = 0;
        try {
            if (enhancementSessionRegistry != null) {
                EnhancementSessionCloseSummary summary = enhancementSessionRegistry.closeAll("reset");
                enhancementSessions = summary.getTotal();
                enhancementClosed = summary.getClosed();
                enhancementFailed = summary.getFailed();
            }
        } catch (Exception ignore) {
            enhancementFailed++;
        }

        // Compatibility fallback for sessions that predate the unified registry.
        try {
            vmToolSessionRegistry.stopAll(instrumentation, transformer, "reset");
        } catch (Exception ignore) {
            // best-effort
        }

        // Compatibility cleanup only; active listener sessions are closed through the unified registry above.
        AgentGlobalState.resetLegacyInterceptorsBestEffort();

        transformer.removeAllEnhancers();

        int retransformCount = 0;
        int skipped = 0;
        try {
            Class<?>[] loaded = instrumentation.getAllLoadedClasses();
            for (Class<?> c : loaded) {
                if (c == null) {
                    continue;
                }
                if (!matchesEnhancedRef(enhanced, c)) {
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
            ", enhancementSessions=" + enhancementSessions +
            ", enhancementClosed=" + enhancementClosed +
            ", enhancementFailed=" + enhancementFailed +
            ", enhancedClasses=" + enhanced.size() +
            ", retransformed=" + retransformCount +
            ", skipped=" + skipped;
    }

    @Override
    public String getDescription() {
        return "Reset all active enhancements (clear sessions, remove enhancers, retransform classes)";
    }

    private static boolean matchesEnhancedRef(Set<SleuthClassFileTransformer.EnhancedClassRef> enhanced, Class<?> clazz) {
        if (enhanced == null || enhanced.isEmpty() || clazz == null) {
            return false;
        }
        for (SleuthClassFileTransformer.EnhancedClassRef ref : enhanced) {
            if (ref != null && ref.matches(clazz)) {
                return true;
            }
        }
        return false;
    }
}
