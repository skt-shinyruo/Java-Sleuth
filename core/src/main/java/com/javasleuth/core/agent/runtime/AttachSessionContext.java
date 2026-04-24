package com.javasleuth.core.agent.runtime;

import com.javasleuth.core.command.CommandProcessor;
import com.javasleuth.core.command.CommandProcessorFactory;
import com.javasleuth.core.command.CommandProcessorFactoryRequest;
import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.session.ClientSessionRegistry;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.util.SleuthLogger;
import com.javasleuth.foundation.util.SleuthThreadFactory;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Attach-scope runtime state holder.
 *
 * <p>Owns the resources created for a single attach lifecycle and provides one idempotent close path.</p>
 */
final class AttachSessionContext implements AutoCloseable {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final SleuthAgentServices services;
    private final ClientSessionRegistry clientSessionRegistry;
    private final MetricsCollector metricsCollector;
    private final JobManager jobManager;
    private final VmToolSessionRegistry vmToolSessionRegistry;
    private final CommandProcessor commandProcessor;
    private final SleuthSpyDispatcher spyDispatcher;
    private final Thread commandThread;
    private final AtomicBoolean commandThreadStarted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private AttachSessionContext(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        SleuthAgentServices services,
        ClientSessionRegistry clientSessionRegistry,
        MetricsCollector metricsCollector,
        JobManager jobManager,
        VmToolSessionRegistry vmToolSessionRegistry,
        CommandProcessor commandProcessor,
        SleuthSpyDispatcher spyDispatcher,
        Thread commandThread
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.services = services;
        this.clientSessionRegistry = clientSessionRegistry;
        this.metricsCollector = metricsCollector;
        this.jobManager = jobManager;
        this.vmToolSessionRegistry = vmToolSessionRegistry;
        this.commandProcessor = commandProcessor;
        this.spyDispatcher = spyDispatcher;
        this.commandThread = commandThread;
    }

    public static AttachSessionContext create(Instrumentation inst, Runnable shutdownHook) {
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
        SleuthSpyDispatcher spyDispatcher = null;
        Thread commandThread = null;
        try {
            inst.addTransformer(transformer, true);

            if (!BootstrapBridge.canEnableEnhancement(BootstrapBridge.SPY_API, null)) {
                String msg = "Java-Sleuth: " + BootstrapBridge.formatDisabledMessage("enhancement commands", BootstrapBridge.SPY_API);
                if (BootstrapBridge.isStrictMode()) {
                    throw new IllegalStateException(msg);
                }
                SleuthLogger.warn(msg);
            }

            spyDispatcher = new SleuthSpyDispatcher();
            try {
                com.javasleuth.bootstrap.spy.SleuthSpyAPI.setSpy(spyDispatcher);
                com.javasleuth.bootstrap.spy.SleuthSpyAPI.init();
            } catch (Throwable t) {
                SleuthLogger.warn("Java-Sleuth: Failed to install SpyAPI dispatcher: " + t.getMessage(), t);
            }

            AuditLogger auditLogger = services.getAuditLogger();
            AuthenticationManager authenticationManager = services.getAuthenticationManager();
            AuthorizationManager authorizationManager = new AuthorizationManager(config, auditLogger, authenticationManager);
            DangerousCommandConfirmationManager dangerousConfirm = services.getDangerousConfirm();

            clientSessionRegistry = new ClientSessionRegistry();
            metricsCollector = new MetricsCollector(config);
            jobManager = new JobManager();
            vmToolSessionRegistry = new VmToolSessionRegistry(spyDispatcher);

            commandProcessor = CommandProcessorFactory.create(
                CommandProcessorFactoryRequest.builder(inst, transformer)
                    .withShutdownHook(shutdownHook)
                    .withConfig(config)
                    .withAuditLogger(auditLogger)
                    .withAuthenticationManager(authenticationManager)
                    .withAuthorizationManager(authorizationManager)
                    .withDangerousConfirm(dangerousConfirm)
                    .withClientSessionRegistry(clientSessionRegistry)
                    .withMetricsCollector(metricsCollector)
                    .withJobManager(jobManager)
                    .withVmToolSessionRegistry(vmToolSessionRegistry)
                    .withPerformanceOptimizer(services.getPerformanceOptimizer())
                    .withSpyDispatcher(spyDispatcher)
                    .build()
            );

            commandThread =
                SleuthThreadFactory.daemonFixed("sleuth-command-processor").newThread(commandProcessor::start);

            return new AttachSessionContext(
                inst,
                transformer,
                services,
                clientSessionRegistry,
                metricsCollector,
                jobManager,
                vmToolSessionRegistry,
                commandProcessor,
                spyDispatcher,
                commandThread
            );
        } catch (Exception e) {
            cleanupStartupFailure(
                inst,
                transformer,
                services,
                clientSessionRegistry,
                metricsCollector,
                jobManager,
                vmToolSessionRegistry,
                commandProcessor,
                spyDispatcher
            );
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

    public SleuthAgentServices getServices() {
        return services;
    }

    public ProductionConfig getConfig() {
        SleuthAgentServices svcs = services;
        return svcs != null ? svcs.getConfig() : null;
    }

    public ClientSessionRegistry getClientSessionRegistry() {
        return clientSessionRegistry;
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public JobManager getJobManager() {
        return jobManager;
    }

    public VmToolSessionRegistry getVmToolSessionRegistry() {
        return vmToolSessionRegistry;
    }

    public CommandProcessor getCommandProcessor() {
        return commandProcessor;
    }

    public SleuthSpyDispatcher getSpyDispatcher() {
        return spyDispatcher;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        destroySpyBestEffort(spyDispatcher);

        Instrumentation inst = instrumentation;
        SleuthClassFileTransformer tx = transformer;
        Set<String> enhanced = snapshotEnhancedClassNames(tx);

        shutdownCommandProcessorBestEffort(commandProcessor);
        shutdownAttachResourcesBestEffort(inst, tx, jobManager, clientSessionRegistry, vmToolSessionRegistry);
        removeEnhancersBestEffort(tx);
        rollbackEnhancedClassesBestEffort(inst, enhanced);
        removeTransformerBestEffort(inst, tx);
        joinCommandThreadBestEffort(commandThread);
        closeServicesBestEffort(services);
    }

    private static void cleanupStartupFailure(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        SleuthAgentServices services,
        ClientSessionRegistry clientSessionRegistry,
        MetricsCollector metricsCollector,
        JobManager jobManager,
        VmToolSessionRegistry vmToolSessionRegistry,
        CommandProcessor commandProcessor,
        SleuthSpyDispatcher spyDispatcher
    ) {
        shutdownCommandProcessorBestEffort(commandProcessor);
        shutdownJobManagerBestEffort(jobManager, "startup_failed");
        shutdownMetricsBestEffort(metricsCollector);
        shutdownVmToolSessionsBestEffort(instrumentation, transformer, vmToolSessionRegistry, "startup_failed");
        shutdownClientSessionsBestEffort(clientSessionRegistry, "startup_failed");
        AgentGlobalState.resetBootstrapAttachStateBestEffort();
        closeServicesBestEffort(services);
        clearDispatcherBestEffort(spyDispatcher);
        destroySpyApiBestEffort();
        removeTransformerBestEffort(instrumentation, transformer);
        removeEnhancersBestEffort(transformer);
    }

    private static Set<String> snapshotEnhancedClassNames(SleuthClassFileTransformer transformer) {
        if (transformer == null) {
            return Collections.emptySet();
        }
        try {
            return new HashSet<>(transformer.getEnhancedClassNames());
        } catch (Exception ignore) {
            return Collections.emptySet();
        }
    }

    private static void destroySpyBestEffort(SleuthSpyDispatcher dispatcher) {
        clearDispatcherBestEffort(dispatcher);
        destroySpyApiBestEffort();
    }

    private static void clearDispatcherBestEffort(SleuthSpyDispatcher dispatcher) {
        try {
            if (dispatcher != null) {
                dispatcher.clear();
            }
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static void destroySpyApiBestEffort() {
        try {
            com.javasleuth.bootstrap.spy.SleuthSpyAPI.destroy();
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private static void shutdownCommandProcessorBestEffort(CommandProcessor commandProcessor) {
        if (commandProcessor == null) {
            return;
        }
        try {
            commandProcessor.shutdownForDetach();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static void shutdownAttachResourcesBestEffort(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        JobManager jobManager,
        ClientSessionRegistry clientSessionRegistry,
        VmToolSessionRegistry vmToolSessionRegistry
    ) {
        shutdownJobManagerBestEffort(jobManager, "shutdown");
        shutdownClientSessionsBestEffort(clientSessionRegistry, "shutdown");
        shutdownVmToolSessionsBestEffort(instrumentation, transformer, vmToolSessionRegistry, "shutdown");
        AgentGlobalState.resetBootstrapAttachStateBestEffort();
    }

    private static void shutdownJobManagerBestEffort(JobManager jobManager, String reason) {
        try {
            if (jobManager != null) {
                jobManager.shutdown(reason);
            }
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static void shutdownClientSessionsBestEffort(ClientSessionRegistry clientSessionRegistry, String reason) {
        try {
            if (clientSessionRegistry != null) {
                clientSessionRegistry.shutdown(reason);
            }
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static void shutdownMetricsBestEffort(MetricsCollector metricsCollector) {
        try {
            if (metricsCollector != null) {
                metricsCollector.shutdown();
            }
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static void shutdownVmToolSessionsBestEffort(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        VmToolSessionRegistry vmToolSessionRegistry,
        String reason
    ) {
        try {
            if (vmToolSessionRegistry != null) {
                vmToolSessionRegistry.shutdown(instrumentation, transformer, reason);
            }
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static void removeEnhancersBestEffort(SleuthClassFileTransformer transformer) {
        if (transformer == null) {
            return;
        }
        try {
            transformer.removeAllEnhancers();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static void rollbackEnhancedClassesBestEffort(Instrumentation instrumentation, Set<String> enhancedClassNames) {
        if (instrumentation == null || enhancedClassNames == null || enhancedClassNames.isEmpty()) {
            return;
        }
        try {
            Class<?>[] loaded = instrumentation.getAllLoadedClasses();
            for (Class<?> c : loaded) {
                if (c == null) {
                    continue;
                }
                if (!enhancedClassNames.contains(c.getName())) {
                    continue;
                }
                if (!instrumentation.isModifiableClass(c)) {
                    continue;
                }
                try {
                    instrumentation.retransformClasses(c);
                } catch (Exception ignore) {
                    // best-effort per-class
                }
            }
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static void removeTransformerBestEffort(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        if (instrumentation == null || transformer == null) {
            return;
        }
        try {
            instrumentation.removeTransformer(transformer);
        } catch (Exception e) {
            SleuthLogger.warn("Java-Sleuth: Failed to remove transformer: " + e.getMessage(), e);
        }
    }

    private static void joinCommandThreadBestEffort(Thread commandThread) {
        if (commandThread == null) {
            return;
        }
        try {
            commandThread.join(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static void closeServicesBestEffort(SleuthAgentServices services) {
        try {
            if (services != null) {
                services.close();
            }
        } catch (Exception ignore) {
            // best-effort
        }
    }
}
