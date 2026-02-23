package com.javasleuth.container;

import com.javasleuth.bootstrap.monitor.BootstrapMonitorConfigStore;
import com.javasleuth.bootstrap.monitor.VmToolInterceptor;
import com.javasleuth.bootstrap.agent.CoreClassLoaderRegistry;
import com.javasleuth.bootstrap.util.SystemPropertyRollbackRegistry;
import com.javasleuth.core.agent.core.BootstrapAttachGateReset;
import com.javasleuth.core.agent.runtime.AgentGlobalState;
import com.javasleuth.core.agent.runtime.SleuthAgentRuntime;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.MonitoringConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.foundation.util.SleuthLogger;
import java.io.Closeable;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java-Sleuth container 入口（composition root）。
 *
 * <p>该入口运行在 bootstrap agent 创建的隔离 ClassLoader 中（URLClassLoader parent=null），负责：
 * 1) 解析/应用 agentArgs
 * 2) 启动每次 attach 对应的 runtime（可关闭、幂等）
 * 3) detach/shutdown 时收口资源回收与全局状态清理，并重置 bootstrap 入口闩锁以支持 re-attach
 */
public final class SleuthAgentContainerEntrypoint {
    private static final AtomicBoolean ATTACHED = new AtomicBoolean(false);
    private static final AtomicReference<SleuthAgentRuntime> RUNTIME = new AtomicReference<>();

    private SleuthAgentContainerEntrypoint() {}

    public static void agentmain(String agentArgs, Instrumentation inst) {
        if (!ATTACHED.compareAndSet(false, true)) {
            SleuthLogger.warn("Java-Sleuth agent is already attached to this JVM");
            return;
        }

        try {
            // bootstrap agent 与 container/core 需要对 agentArgs 的解释保持一致。
            SystemPropertyRollbackRegistry.applyAndRegisterIfAbsent(agentArgs);
            syncBootstrapMonitoringConfig();

            SleuthAgentRuntime runtime = SleuthAgentRuntime.start(inst, SleuthAgentContainerEntrypoint::shutdown);
            RUNTIME.set(runtime);
            SleuthLogger.info("Java-Sleuth agent attached successfully (container)");
        } catch (Exception e) {
            SleuthLogger.error("Failed to start Java-Sleuth agent (container): " + e.getMessage(), e);
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

    /**
     * Best-effort safe shutdown.
     *
     * <p>核心目标：detach→re-attach 幂等；不遗留线程/transformer/认证会话/监控注册表等全局状态。</p>
     */
    public static void shutdown() {
        SleuthAgentRuntime runtime = RUNTIME.getAndSet(null);
        Instrumentation instrumentation = runtime != null ? runtime.getInstrumentation() : null;
        try {
            if (runtime != null) {
                runtime.close();
            } else {
                // 即使 runtime 未初始化（或部分失败），也清理 bootstrap 侧静态注册表，避免状态泄漏。
                clearBootstrapRegistriesBestEffort();
            }
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
                CoreClassLoaderRegistry.onCoreShutdown(SleuthAgentContainerEntrypoint.class.getClassLoader());
            } catch (Throwable ignore) {
                // best-effort
            }
            // best-effort：关闭隔离 classloader 的底层 jar 句柄，降低 Windows/容器环境中文件占用问题。
            closeOwnClassLoaderBestEffort();
        }
        SleuthLogger.info("Java-Sleuth agent shutdown (container)");
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

    private static void closeOwnClassLoaderBestEffort() {
        try {
            ClassLoader cl = SleuthAgentContainerEntrypoint.class.getClassLoader();
            if (cl instanceof Closeable) {
                ((Closeable) cl).close();
            }
        } catch (Throwable ignore) {
            // best-effort
        }
    }
}
