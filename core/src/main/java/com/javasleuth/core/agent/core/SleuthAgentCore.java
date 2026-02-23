package com.javasleuth.core.agent.core;

/**
 * Agent 核心入口（attach/premain），作为核心组合根（composition root）。
 *
 * <p>该类负责组装并启动命令服务与 transformer，尽量避免将单例获取与全局副作用散落到各业务对象构造阶段。</p>
 */
import com.javasleuth.bootstrap.monitor.BootstrapMonitorConfigStore;
import com.javasleuth.bootstrap.monitor.VmToolInterceptor;
import com.javasleuth.bootstrap.agent.CoreClassLoaderRegistry;
import com.javasleuth.bootstrap.util.SystemPropertyRollbackRegistry;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.MonitoringConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.foundation.util.SleuthLogger;
import com.javasleuth.core.agent.runtime.AgentGlobalState;
import com.javasleuth.core.agent.runtime.SleuthAgentRuntime;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

public class SleuthAgentCore {
    private static final AtomicBoolean ATTACHED = new AtomicBoolean(false);
    private static final AtomicReference<SleuthAgentRuntime> RUNTIME = new AtomicReference<>();

    public static void agentmain(String agentArgs, Instrumentation inst) {
        if (!ATTACHED.compareAndSet(false, true)) {
            SleuthLogger.warn("Java-Sleuth agent is already attached to this JVM");
            return;
        }

        try {
            SystemPropertyRollbackRegistry.applyAndRegisterIfAbsent(agentArgs);
            syncBootstrapMonitoringConfig();
            SleuthAgentRuntime runtime = SleuthAgentRuntime.start(inst, SleuthAgentCore::shutdown);
            RUNTIME.set(runtime);
            SleuthLogger.info("Java-Sleuth agent attached successfully");
        } catch (Exception e) {
            SleuthLogger.error("Failed to start Java-Sleuth agent: " + e.getMessage(), e);
            try {
                shutdown();
            } catch (Exception ignore) {
                // best-effort
            }
        }
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        agentmain(agentArgs, inst);
    }

    public static SleuthClassFileTransformer getTransformer() {
        SleuthAgentRuntime runtime = RUNTIME.get();
        return runtime != null ? runtime.getTransformer() : null;
    }

    public static Instrumentation getInstrumentation() {
        SleuthAgentRuntime runtime = RUNTIME.get();
        return runtime != null ? runtime.getInstrumentation() : null;
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
        SleuthAgentRuntime runtime = RUNTIME.getAndSet(null);
        Instrumentation instrumentation = runtime != null ? runtime.getInstrumentation() : null;
        try {
            if (runtime != null) {
                runtime.close();
                return;
            }
            // Even if runtime is not initialized (or partially failed), clear bootstrap-side registries
            // best-effort to avoid state leakage across tests / detach → re-attach.
            clearBootstrapRegistriesBestEffort();
        } catch (Exception ignore) {
            clearBootstrapRegistriesBestEffort();
        } finally {
            ATTACHED.set(false);
            // 清理 attach 级别的 bootstrap 配置 Store，避免跨 attach 漂移。
            try {
                BootstrapMonitorConfigStore.clear();
            } catch (Exception ignore) {
                // best-effort
            }
            // 回滚本次 attach 写入的 sysprop，避免跨 attach 漂移。
            SystemPropertyRollbackRegistry.rollbackAndClearBestEffort();
            // 重置 bootstrap 入口闩锁：允许同 JVM detach 后 re-attach。
            BootstrapAttachGateReset.resetBestEffort(instrumentation);
            // 通知 bootstrap registry 释放/关闭本次 attach 的隔离 ClassLoader（best-effort）。
            try {
                CoreClassLoaderRegistry.onCoreShutdown(SleuthAgentCore.class.getClassLoader());
            } catch (Throwable ignore) {
                // best-effort
            }
        }
        SleuthLogger.info("Java-Sleuth agent shutdown");
    }

    private static void clearBootstrapRegistriesBestEffort() {
        try {
            VmToolInterceptor.clearAll();
        } catch (Exception ignore) {
            // best-effort
        }
        AgentGlobalState.resetInterceptorsBestEffort();
    }

    private static void syncBootstrapMonitoringConfig() {
        try {
            ProductionConfig cfg = ProductionConfig.getInstance();
            SleuthConfig typed = SleuthConfigParser.parse(cfg.snapshot());
            MonitoringConfig monitoring = typed.monitoring();
            // 保持与旧版 setIfAbsent 行为兼容：若用户显式设置 sysprop，则优先使用 sysprop。
            if (System.getProperty("sleuth.monitoring.watch.drop.on.full") == null) {
                BootstrapMonitorConfigStore.setWatchDropOnFull(monitoring.isWatchDropOnFull());
            }
            if (System.getProperty("sleuth.monitoring.trace.drop.on.full") == null) {
                BootstrapMonitorConfigStore.setTraceDropOnFull(monitoring.isTraceDropOnFull());
            }
            if (System.getProperty("sleuth.monitoring.trace.sample.rate") == null) {
                BootstrapMonitorConfigStore.setTraceSampleRate(monitoring.getTraceSampleRate());
            }
            if (System.getProperty("sleuth.monitoring.monitor.sample.rate") == null) {
                BootstrapMonitorConfigStore.setMonitorSampleRate(monitoring.getMonitorSampleRate());
            }
        } catch (Exception ignore) {
            // best-effort
        }
    }
}
