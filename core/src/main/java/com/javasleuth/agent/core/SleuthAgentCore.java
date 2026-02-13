package com.javasleuth.agent.core;

/**
 * Agent 核心入口（attach/premain），作为核心组合根（composition root）。
 *
 * <p>该类负责组装并启动命令服务与 transformer，尽量避免将单例获取与全局副作用散落到各业务对象构造阶段。</p>
 */
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.command.CommandProcessor;
import com.javasleuth.command.CommandProcessorFactory;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.security.AuditLogger;
import com.javasleuth.security.AuthenticationManager;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.DangerousCommandConfirmationManager;
import com.javasleuth.security.RequestSecurityManager;
import com.javasleuth.command.session.ClientSessionRegistry;
import com.javasleuth.util.AgentArgsApplier;
import com.javasleuth.util.SleuthLogger;
import com.javasleuth.util.SleuthThreadFactory;
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
            AgentArgsApplier.applyToSystemProperties(agentArgs);
            syncBootstrapMonitoringConfig();

            // Initialize the class file transformer
            transformer = new SleuthClassFileTransformer();
            inst.addTransformer(transformer, true);

            ProductionConfig config = ProductionConfig.getInstance();
            AuditLogger auditLogger = AuditLogger.getInstance();
            AuthenticationManager authenticationManager = AuthenticationManager.getInstance();
            AuthorizationManager authorizationManager = new AuthorizationManager(config, auditLogger, authenticationManager);
            RequestSecurityManager requestSecurityManager = new RequestSecurityManager(config, auditLogger);
            DangerousCommandConfirmationManager dangerousConfirm = DangerousCommandConfirmationManager.getInstance();
            ClientSessionRegistry clientSessionRegistry = ClientSessionRegistry.getInstance();
            MetricsCollector metricsCollector = new MetricsCollector();

            commandProcessor = CommandProcessorFactory.create(
                inst,
                transformer,
                SleuthAgentCore::shutdown,
                config,
                auditLogger,
                authenticationManager,
                authorizationManager,
                requestSecurityManager,
                dangerousConfirm,
                clientSessionRegistry,
                metricsCollector
            );

            Thread commandThread = SleuthThreadFactory.daemonFixed("sleuth-command-processor").newThread(commandProcessor::start);

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
            com.javasleuth.command.JobManager.getInstance().shutdown("shutdown");
        } catch (Exception ignore) {
            // best-effort
        }
        // vmtool track sessions keep both core-side enhancer references and bootstrap-side weak caches.
        // Clear them explicitly to avoid detach → re-attach state accumulation.
        try {
            SleuthClassFileTransformer t = tx;
            if (t != null) {
                com.javasleuth.vmtool.VmToolSessionRegistry.getInstance().stopAll(inst, t, "shutdown");
            }
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            com.javasleuth.monitor.VmToolInterceptor.clearAll();
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

    private static void syncBootstrapMonitoringConfig() {
        try {
            ProductionConfig cfg = ProductionConfig.getInstance();
            setIfAbsent("sleuth.monitoring.watch.drop.on.full", String.valueOf(cfg.isWatchDropOnFull()));
            setIfAbsent("sleuth.monitoring.trace.drop.on.full", String.valueOf(cfg.isTraceDropOnFull()));
            setIfAbsent("sleuth.monitoring.trace.sample.rate", String.valueOf(cfg.getTraceSampleRate()));
            setIfAbsent("sleuth.monitoring.monitor.sample.rate", String.valueOf(cfg.getMonitorSampleRate()));
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static void setIfAbsent(String key, String value) {
        if (key == null || key.trim().isEmpty() || value == null) {
            return;
        }
        if (System.getProperty(key) != null) {
            return;
        }
        System.setProperty(key, value);
    }
}
