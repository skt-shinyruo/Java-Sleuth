package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.command.JobManager;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.monitor.MonitorInterceptor;
import com.javasleuth.monitor.StackInterceptor;
import com.javasleuth.monitor.TraceInterceptor;
import com.javasleuth.monitor.WatchInterceptor;
import com.javasleuth.monitor.TtInterceptor;
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

    public ResetCommand(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
    }

    @Override
    public String execute(String[] args) {
        int stoppedJobs = JobManager.getInstance().stopAll("reset");

        Set<String> enhanced = new HashSet<>(transformer.getEnhancedClassNames());

        // Clear vmtool sessions first (remove its enhancers best-effort).
        try {
            VmToolSessionRegistry.getInstance().stopAll(instrumentation, transformer, "reset");
        } catch (Exception ignore) {
            // best-effort
        }

        // Clear interceptor sessions first to reduce further event publishing.
        WatchInterceptor.unregisterAllWatches();
        TraceInterceptor.unregisterAllTraces();
        MonitorInterceptor.unregisterAllMonitors();
        TtInterceptor.unregisterAll();
        StackInterceptor.unregisterAll();

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
