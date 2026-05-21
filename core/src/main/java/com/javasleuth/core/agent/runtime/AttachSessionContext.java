package com.javasleuth.core.agent.runtime;

import com.javasleuth.bootstrap.agent.AgentLifecycle;
import com.javasleuth.core.command.CommandProcessor;
import com.javasleuth.core.command.CommandProcessorFactory;
import com.javasleuth.core.command.CommandProcessorFactoryRequest;
import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.session.ClientSessionRegistry;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionCloseSummary;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
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
    private static final long COMMAND_PROCESSOR_STARTUP_TIMEOUT_MS = 5000L;

    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final SleuthAgentServices services;
    private final ClientSessionRegistry clientSessionRegistry;
    private final MetricsCollector metricsCollector;
    private final JobManager jobManager;
    private final VmToolSessionRegistry vmToolSessionRegistry;
    private final EnhancementSessionRegistry enhancementSessionRegistry;
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
        EnhancementSessionRegistry enhancementSessionRegistry,
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
        this.enhancementSessionRegistry = enhancementSessionRegistry;
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
        EnhancementSessionRegistry enhancementSessionRegistry = null;
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
            enhancementSessionRegistry = new EnhancementSessionRegistry();

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
                    .withEnhancementSessionRegistry(enhancementSessionRegistry)
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
                enhancementSessionRegistry,
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
                enhancementSessionRegistry,
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
            commandProcessor.prepareStartupSignal();
            t.start();
            commandProcessor.awaitStartupOrThrow(COMMAND_PROCESSOR_STARTUP_TIMEOUT_MS);
        } catch (IllegalThreadStateException ignore) {
            // already started
        } catch (Exception e) {
            throw new IllegalStateException("command processor thread failed to start", e);
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

    public EnhancementSessionRegistry getEnhancementSessionRegistry() {
        return enhancementSessionRegistry;
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
        Instrumentation inst = instrumentation;
        SleuthClassFileTransformer tx = transformer;
        CleanupResult.Builder cleanup = CleanupResult.builder("attach-session", "shutdown");
        Set<SleuthClassFileTransformer.EnhancedClassRef> enhanced = snapshotEnhancedClassRefs(tx, cleanup);

        shutdownCommandProcessor(cleanup, commandProcessor);
        shutdownAttachResources(cleanup, inst, tx, jobManager, clientSessionRegistry, vmToolSessionRegistry, enhancementSessionRegistry);
        destroySpy(cleanup, spyDispatcher);
        removeEnhancers(cleanup, tx);
        rollbackEnhancedClasses(cleanup, inst, enhanced);
        removeTransformer(cleanup, inst, tx);
        joinCommandThread(cleanup, commandThread);
        closeServices(cleanup, services);
        publishCleanupResult(cleanup.build());
    }

    private static void cleanupStartupFailure(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        SleuthAgentServices services,
        ClientSessionRegistry clientSessionRegistry,
        MetricsCollector metricsCollector,
        JobManager jobManager,
        VmToolSessionRegistry vmToolSessionRegistry,
        EnhancementSessionRegistry enhancementSessionRegistry,
        CommandProcessor commandProcessor,
        SleuthSpyDispatcher spyDispatcher
    ) {
        CleanupResult.Builder cleanup = CleanupResult.builder("attach-session", "startup_failed");
        shutdownCommandProcessor(cleanup, commandProcessor);
        shutdownEnhancementSessions(cleanup, enhancementSessionRegistry, "startup_failed");
        shutdownJobManager(cleanup, jobManager, "startup_failed");
        shutdownMetrics(cleanup, metricsCollector);
        shutdownVmToolSessions(cleanup, instrumentation, transformer, vmToolSessionRegistry, "startup_failed");
        shutdownClientSessions(cleanup, clientSessionRegistry, "startup_failed");
        resetBootstrapAttachState(cleanup);
        closeServices(cleanup, services);
        clearDispatcher(cleanup, spyDispatcher);
        destroySpyApi(cleanup);
        removeTransformer(cleanup, instrumentation, transformer);
        removeEnhancers(cleanup, transformer);
        publishCleanupResult(cleanup.build());
    }

    private static Set<SleuthClassFileTransformer.EnhancedClassRef> snapshotEnhancedClassRefs(
        SleuthClassFileTransformer transformer,
        CleanupResult.Builder cleanup
    ) {
        if (transformer == null) {
            cleanup.skipped("snapshot-enhanced-classes", "transformer unavailable");
            return Collections.emptySet();
        }
        try {
            Set<SleuthClassFileTransformer.EnhancedClassRef> names =
                new HashSet<SleuthClassFileTransformer.EnhancedClassRef>(transformer.getEnhancedClassRefs());
            cleanup.success("snapshot-enhanced-classes");
            return names;
        } catch (Throwable t) {
            cleanup.failure("snapshot-enhanced-classes", t);
            return Collections.emptySet();
        }
    }

    private static void destroySpy(CleanupResult.Builder cleanup, SleuthSpyDispatcher dispatcher) {
        clearDispatcher(cleanup, dispatcher);
        destroySpyApi(cleanup);
    }

    private static void clearDispatcher(CleanupResult.Builder cleanup, SleuthSpyDispatcher dispatcher) {
        if (dispatcher == null) {
            cleanup.skipped("clear-spy-dispatcher", "dispatcher unavailable");
            return;
        }
        recordStep(cleanup, "clear-spy-dispatcher", () -> {
            dispatcher.clear();
        });
    }

    private static void destroySpyApi(CleanupResult.Builder cleanup) {
        recordStep(cleanup, "destroy-spy-api", () -> {
            com.javasleuth.bootstrap.spy.SleuthSpyAPI.destroy();
        });
    }

    private static void shutdownCommandProcessor(CleanupResult.Builder cleanup, CommandProcessor commandProcessor) {
        if (commandProcessor == null) {
            cleanup.skipped("shutdown-command-processor", "command processor unavailable");
            return;
        }
        recordStep(cleanup, "shutdown-command-processor", () -> {
            commandProcessor.shutdownForDetach();
        });
    }

    private static void shutdownAttachResources(
        CleanupResult.Builder cleanup,
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        JobManager jobManager,
        ClientSessionRegistry clientSessionRegistry,
        VmToolSessionRegistry vmToolSessionRegistry,
        EnhancementSessionRegistry enhancementSessionRegistry
    ) {
        shutdownEnhancementSessions(cleanup, enhancementSessionRegistry, "shutdown");
        shutdownJobManager(cleanup, jobManager, "shutdown");
        shutdownClientSessions(cleanup, clientSessionRegistry, "shutdown");
        shutdownVmToolSessions(cleanup, instrumentation, transformer, vmToolSessionRegistry, "shutdown");
        resetBootstrapAttachState(cleanup);
    }

    private static void shutdownEnhancementSessions(
        CleanupResult.Builder cleanup,
        EnhancementSessionRegistry enhancementSessionRegistry,
        String reason
    ) {
        if (enhancementSessionRegistry == null) {
            cleanup.skipped("shutdown-enhancement-sessions", "registry unavailable");
            return;
        }
        recordStep(cleanup, "shutdown-enhancement-sessions", () -> {
            EnhancementSessionCloseSummary summary = enhancementSessionRegistry.closeAll(reason);
            if (summary != null && summary.getFailed() > 0) {
                throw new IllegalStateException(formatEnhancementCloseSummary(summary));
            }
        });
    }

    private static void shutdownJobManager(CleanupResult.Builder cleanup, JobManager jobManager, String reason) {
        if (jobManager == null) {
            cleanup.skipped("shutdown-job-manager", "job manager unavailable");
            return;
        }
        recordStep(cleanup, "shutdown-job-manager", () -> {
            jobManager.shutdown(reason);
        });
    }

    private static void shutdownClientSessions(CleanupResult.Builder cleanup, ClientSessionRegistry clientSessionRegistry, String reason) {
        if (clientSessionRegistry == null) {
            cleanup.skipped("shutdown-client-sessions", "client registry unavailable");
            return;
        }
        recordStep(cleanup, "shutdown-client-sessions", () -> {
            clientSessionRegistry.shutdown(reason);
        });
    }

    private static void shutdownMetrics(CleanupResult.Builder cleanup, MetricsCollector metricsCollector) {
        if (metricsCollector == null) {
            cleanup.skipped("shutdown-metrics", "metrics collector unavailable");
            return;
        }
        recordStep(cleanup, "shutdown-metrics", () -> {
            metricsCollector.shutdown();
        });
    }

    private static void shutdownVmToolSessions(
        CleanupResult.Builder cleanup,
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        VmToolSessionRegistry vmToolSessionRegistry,
        String reason
    ) {
        if (vmToolSessionRegistry == null) {
            cleanup.skipped("shutdown-vmtool-sessions", "vmtool registry unavailable");
            return;
        }
        recordStep(cleanup, "shutdown-vmtool-sessions", () -> {
            vmToolSessionRegistry.shutdown(instrumentation, transformer, reason);
        });
    }

    private static void resetBootstrapAttachState(CleanupResult.Builder cleanup) {
        recordStep(cleanup, "reset-bootstrap-attach-state", () -> {
            AgentGlobalState.resetBootstrapAttachStateBestEffort();
        });
    }

    private static void removeEnhancers(CleanupResult.Builder cleanup, SleuthClassFileTransformer transformer) {
        if (transformer == null) {
            cleanup.skipped("remove-enhancers", "transformer unavailable");
            return;
        }
        recordStep(cleanup, "remove-enhancers", () -> {
            transformer.removeAllEnhancers();
        });
    }

    private static void rollbackEnhancedClasses(
        CleanupResult.Builder cleanup,
        Instrumentation instrumentation,
        Set<SleuthClassFileTransformer.EnhancedClassRef> enhancedClassRefs
    ) {
        if (instrumentation == null) {
            cleanup.skipped("rollback-enhanced-classes", "instrumentation unavailable");
            return;
        }
        if (enhancedClassRefs == null || enhancedClassRefs.isEmpty()) {
            cleanup.skipped("rollback-enhanced-classes", "no enhanced classes");
            return;
        }

        Class<?>[] loaded;
        try {
            loaded = instrumentation.getAllLoadedClasses();
            cleanup.success("snapshot-loaded-classes-for-rollback");
        } catch (Throwable t) {
            cleanup.failure("snapshot-loaded-classes-for-rollback", t);
            return;
        }

        for (Class<?> c : loaded) {
            if (c == null) {
                continue;
            }
            String className = c.getName();
            if (!matchesEnhancedRef(enhancedClassRefs, c)) {
                continue;
            }
            boolean modifiable;
            try {
                modifiable = instrumentation.isModifiableClass(c);
            } catch (Throwable t) {
                cleanup.failure("check-modifiable-class:" + className, t);
                continue;
            }
            if (!modifiable) {
                cleanup.skipped("rollback-enhanced-class:" + className, "class is not modifiable");
                continue;
            }
            recordStep(cleanup, "rollback-enhanced-class:" + className, () -> {
                instrumentation.retransformClasses(c);
            });
        }
    }

    private static boolean matchesEnhancedRef(Set<SleuthClassFileTransformer.EnhancedClassRef> refs, Class<?> clazz) {
        if (refs == null || refs.isEmpty() || clazz == null) {
            return false;
        }
        for (SleuthClassFileTransformer.EnhancedClassRef ref : refs) {
            if (ref != null && ref.matches(clazz)) {
                return true;
            }
        }
        return false;
    }

    private static void removeTransformer(
        CleanupResult.Builder cleanup,
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer
    ) {
        if (instrumentation == null || transformer == null) {
            cleanup.skipped("remove-transformer", instrumentation == null ? "instrumentation unavailable" : "transformer unavailable");
            return;
        }
        recordStep(cleanup, "remove-transformer", () -> {
            boolean removed = instrumentation.removeTransformer(transformer);
            if (!removed) {
                throw new IllegalStateException("removeTransformer returned false");
            }
        });
    }

    private static void joinCommandThread(CleanupResult.Builder cleanup, Thread commandThread) {
        if (commandThread == null) {
            cleanup.skipped("join-command-thread", "command thread unavailable");
            return;
        }
        recordStep(cleanup, "join-command-thread", () -> {
            commandThread.join(1000);
        });
    }

    private static void closeServices(CleanupResult.Builder cleanup, SleuthAgentServices services) {
        if (services == null) {
            cleanup.skipped("close-services", "services unavailable");
            return;
        }
        recordStep(cleanup, "close-services", () -> {
            services.close();
        });
    }

    private static void recordStep(CleanupResult.Builder cleanup, String name, CleanupAction action) {
        try {
            action.run();
            cleanup.success(name);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            cleanup.failure(name, ie);
        } catch (Throwable t) {
            cleanup.failure(name, t);
        }
    }

    private static void publishCleanupResult(CleanupResult result) {
        AgentGlobalState.recordRuntimeCleanupResult(result);
        try {
            AgentLifecycle.recordRuntimeCleanupResult(result.getStatusName(), result.formatSummary());
        } catch (Throwable t) {
            SleuthLogger.warn("Java-Sleuth: Failed to publish cleanup result to bootstrap lifecycle: " + t.getMessage(), t);
        }
        if (result.isDegraded()) {
            String message = "Java-Sleuth: attach cleanup completed with failures: " + result.formatFailures();
            System.err.println(message);
            SleuthLogger.warn(message);
        } else {
            SleuthLogger.debug("Java-Sleuth: attach cleanup completed: " + result.formatSummary());
        }
    }

    private static String formatEnhancementCloseSummary(EnhancementSessionCloseSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("enhancement session cleanup failed")
            .append(" total=").append(summary.getTotal())
            .append(" closed=").append(summary.getClosed())
            .append(" missing=").append(summary.getMissing())
            .append(" failed=").append(summary.getFailed());
        if (!summary.getFailureMessages().isEmpty()) {
            sb.append(" failures=").append(summary.getFailureMessages());
        }
        return sb.toString();
    }

    private interface CleanupAction {
        void run() throws Throwable;
    }
}
