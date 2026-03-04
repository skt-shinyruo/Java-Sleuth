package com.javasleuth.core.agent.runtime;

import com.javasleuth.core.command.CommandProcessor;
import com.javasleuth.core.command.CommandProcessorFactory;
import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.session.ClientSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.RequestSecurityManager;
import com.javasleuth.foundation.util.SleuthLogger;
import com.javasleuth.foundation.util.SleuthThreadFactory;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import java.lang.instrument.Instrumentation;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sleuth agent runtime container (per attach lifecycle).
 *
 * <p>Goal: compress runtime state into a single object and provide a unified, idempotent close() path.
 */
public final class SleuthAgentRuntime implements AutoCloseable {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final CommandProcessor commandProcessor;
    private final ClientSessionRegistry clientSessionRegistry;
    private final JobManager jobManager;
    private final VmToolSessionRegistry vmToolSessionRegistry;
    private final SleuthAgentServices services;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread commandThread;
    private final AtomicBoolean commandThreadStarted = new AtomicBoolean(false);

    private SleuthAgentRuntime(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        CommandProcessor commandProcessor,
        ClientSessionRegistry clientSessionRegistry,
        JobManager jobManager,
        VmToolSessionRegistry vmToolSessionRegistry,
        SleuthAgentServices services,
        Thread commandThread
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.commandProcessor = commandProcessor;
        this.clientSessionRegistry = clientSessionRegistry;
        this.jobManager = jobManager;
        this.vmToolSessionRegistry = vmToolSessionRegistry;
        this.services = services;
        this.commandThread = commandThread;
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

        SleuthAgentServices services = SleuthAgentServices.createDefault();
        ProductionConfig config = services.getConfig();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        ClientSessionRegistry clientSessionRegistry = null;
        MetricsCollector metricsCollector = null;
        JobManager jobManager = null;
        VmToolSessionRegistry vmToolSessionRegistry = null;
        CommandProcessor commandProcessor = null;
        Thread commandThread = null;
        try {
            inst.addTransformer(transformer, true);

            // Bootstrap bridge is a hard precondition for starting the agent in isolated mode.
            // If the bridge is missing, any bytecode enhancement that injects com.javasleuth.bootstrap.* calls
            // may crash the target application at runtime (NoClassDefFoundError/LinkageError).
            if (!BootstrapBridge.canEnableEnhancement(BootstrapBridge.TRACE_INTERCEPTOR, null)) {
                String msg = "Java-Sleuth: " + BootstrapBridge.formatDisabledMessage("enhancement commands", BootstrapBridge.TRACE_INTERCEPTOR);
                if (BootstrapBridge.isStrictMode()) {
                    throw new IllegalStateException(msg);
                }
                SleuthLogger.warn(msg);
            }

            AuditLogger auditLogger = services.getAuditLogger();
            AuthenticationManager authenticationManager = services.getAuthenticationManager();
            AuthorizationManager authorizationManager = new AuthorizationManager(config, auditLogger, authenticationManager);
            RequestSecurityManager requestSecurityManager = new RequestSecurityManager(config, auditLogger);
            DangerousCommandConfirmationManager dangerousConfirm = services.getDangerousConfirm();

            // Per-runtime state (avoid permanent singleton for tests/detach→re-attach).
            clientSessionRegistry = new ClientSessionRegistry();
            metricsCollector = new MetricsCollector(config);
            jobManager = new JobManager();
            vmToolSessionRegistry = new VmToolSessionRegistry();

            commandProcessor = CommandProcessorFactory.create(
                inst,
                transformer,
                shutdownHook,
                config,
                auditLogger,
                authenticationManager,
                authorizationManager,
                requestSecurityManager,
                dangerousConfirm,
                clientSessionRegistry,
                metricsCollector,
                jobManager,
                vmToolSessionRegistry,
                services.getPerformanceOptimizer()
            );

            commandThread =
                SleuthThreadFactory.daemonFixed("sleuth-command-processor").newThread(commandProcessor::start);

            return new SleuthAgentRuntime(
                inst,
                transformer,
                commandProcessor,
                clientSessionRegistry,
                jobManager,
                vmToolSessionRegistry,
                services,
                commandThread
            );
        } catch (Exception e) {
            // best-effort cleanup on partial init
            try {
                if (commandProcessor != null) {
                    commandProcessor.shutdownForDetach();
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (jobManager != null) {
                    jobManager.shutdown("startup_failed");
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (metricsCollector != null) {
                    metricsCollector.shutdown();
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (vmToolSessionRegistry != null) {
                    vmToolSessionRegistry.shutdown(inst, transformer, "startup_failed");
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (clientSessionRegistry != null) {
                    clientSessionRegistry.shutdown("startup_failed");
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                if (services != null) {
                    services.close();
                }
            } catch (Exception ignore) {
                // ignore
            }
            try {
                inst.removeTransformer(transformer);
            } catch (Exception ignore) {
                // ignore
            }
            try {
                transformer.removeAllEnhancers();
            } catch (Exception ignore) {
                // ignore
            }
            throw e;
        }
    }

    public void startCommandProcessorAsync() {
        Thread t = commandThread;
        if (t == null) {
            return;
        }
        if (!commandThreadStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            t.start();
        } catch (IllegalThreadStateException ignore) {
            // already started
        } catch (Exception e) {
            SleuthLogger.warn("Failed to start command processor thread: " + e.getMessage(), e);
        }
    }

    public Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public SleuthClassFileTransformer getTransformer() {
        return transformer;
    }

    public CommandProcessor getCommandProcessor() {
        return commandProcessor;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        Instrumentation inst = instrumentation;
        SleuthClassFileTransformer tx = transformer;

        // Snapshot enhanced class names before removing state.
        Set<String> enhanced = java.util.Collections.emptySet();
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
                commandProcessor.shutdownForDetach();
            } catch (Exception ignore) {
                // best-effort
            }
        }

        // 2) Stop background jobs / clear session registries.
        try {
            JobManager jm = jobManager;
            if (jm != null) {
                jm.shutdown("shutdown");
            }
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            ClientSessionRegistry csr = clientSessionRegistry;
            if (csr != null) {
                csr.shutdown("shutdown");
            }
        } catch (Exception ignore) {
            // best-effort
        }

        // vmtool track sessions keep both core-side enhancer references and bootstrap-side weak caches.
        // Clear them explicitly to avoid detach → re-attach state accumulation.
        try {
            SleuthClassFileTransformer t = tx;
            if (t != null) {
                VmToolSessionRegistry r = vmToolSessionRegistry;
                if (r != null) {
                    r.shutdown(inst, t, "shutdown");
                }
            }
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            com.javasleuth.bootstrap.monitor.VmToolInterceptor.clearAll();
        } catch (Exception ignore) {
            // best-effort
        }
        AgentGlobalState.resetInterceptorsBestEffort();

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

        // Best-effort: wait for command thread to exit quickly (avoid keeping references alive).
        Thread t = commandThread;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception ignore) {
                // ignore
            }
        }

        try {
            SleuthAgentServices svcs = services;
            if (svcs != null) {
                svcs.close();
            }
        } catch (Exception ignore) {
            // best-effort
        }
    }
}
