package com.javasleuth.core.agent.core;

import com.javasleuth.bootstrap.agent.CoreClassLoaderRegistry;
import com.javasleuth.bootstrap.monitor.BootstrapMonitorConfigStore;
import com.javasleuth.bootstrap.monitor.VmToolInterceptor;
import com.javasleuth.bootstrap.util.SystemPropertyRollbackRegistry;
import com.javasleuth.core.agent.runtime.AgentGlobalState;
import com.javasleuth.core.agent.runtime.BootstrapMonitoringConfigSync;
import com.javasleuth.core.agent.runtime.SleuthAgentRuntime;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.util.SleuthLogger;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 共享 attach 入口逻辑（SSOT），用于收敛 {@link SleuthAgentCore} 与 container 入口的重复实现。
 *
 * <p>该类位于 core 模块，便于 container（依赖 core）复用；同时避免 core 反向依赖 container。</p>
 *
 * <p>注意：该逻辑处于 agent 生命周期边界，必须 best-effort、幂等、可用于 detach→re-attach。</p>
 */
public final class SleuthAgentEntrypointSupport {
    private SleuthAgentEntrypointSupport() {}

    public static void agentmain(
        String agentArgs,
        Instrumentation inst,
        AtomicBoolean attachedGate,
        AtomicReference<SleuthAgentRuntime> runtimeRef,
        Runnable shutdownHook,
        String alreadyAttachedWarn,
        String attachedOkInfo,
        String startFailedPrefix
    ) {
        if (attachedGate == null) {
            throw new IllegalArgumentException("attachedGate is required");
        }
        if (runtimeRef == null) {
            throw new IllegalArgumentException("runtimeRef is required");
        }

        if (!attachedGate.compareAndSet(false, true)) {
            SleuthLogger.warn(alreadyAttachedWarn != null ? alreadyAttachedWarn : "Java-Sleuth agent is already attached to this JVM");
            return;
        }

        try {
            // bootstrap agent 与 container/core 需要对 agentArgs 的解释保持一致。
            SystemPropertyRollbackRegistry.applyAndRegisterIfAbsent(agentArgs);
            syncBootstrapMonitoringConfig();

            SleuthAgentRuntime runtime = SleuthAgentRuntime.start(inst, shutdownHook);
            runtimeRef.set(runtime);
            if (attachedOkInfo != null && !attachedOkInfo.trim().isEmpty()) {
                SleuthLogger.info(attachedOkInfo);
            }
        } catch (Exception e) {
            String prefix = startFailedPrefix != null ? startFailedPrefix : "Failed to start Java-Sleuth agent: ";
            SleuthLogger.error(prefix + e.getMessage(), e);
            try {
                if (shutdownHook != null) {
                    shutdownHook.run();
                } else {
                    shutdown(attachedGate, runtimeRef, null, null, null);
                }
            } catch (Exception ignore) {
                // best-effort
            }
        }
    }

    public static void premain(
        String agentArgs,
        Instrumentation inst,
        AtomicBoolean attachedGate,
        AtomicReference<SleuthAgentRuntime> runtimeRef,
        Runnable shutdownHook,
        String alreadyAttachedWarn,
        String attachedOkInfo,
        String startFailedPrefix
    ) {
        agentmain(
            agentArgs,
            inst,
            attachedGate,
            runtimeRef,
            shutdownHook,
            alreadyAttachedWarn,
            attachedOkInfo,
            startFailedPrefix
        );
    }

    public static void shutdown(
        AtomicBoolean attachedGate,
        AtomicReference<SleuthAgentRuntime> runtimeRef,
        ClassLoader selfClassLoader,
        String shutdownOkInfo,
        Runnable extraFinallyAction
    ) {
        if (attachedGate == null) {
            return;
        }
        if (runtimeRef == null) {
            return;
        }

        SleuthAgentRuntime runtime = runtimeRef.getAndSet(null);
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
            attachedGate.set(false);
            // 清理 attach 级别的 bootstrap 配置 Store，避免跨 attach 漂移。
            try {
                BootstrapMonitorConfigStore.clear();
            } catch (Exception ignore) {
                // best-effort
            }
            // 回滚本次 attach 写入的 sysprop，避免跨 attach 漂移。
            SystemPropertyRollbackRegistry.rollbackAndClearBestEffort();
            // 重置 ProductionConfig 单例：确保 detach→re-attach 时能重新加载 configFile/sysprop 基线。
            try {
                ProductionConfig.resetInstanceForDetach();
            } catch (Exception ignore) {
                // best-effort
            }
            // 重置 bootstrap 入口闩锁：允许同 JVM detach 后 re-attach。
            BootstrapAttachGateReset.resetBestEffort(instrumentation);
            // 通知 bootstrap registry 释放/关闭本次 attach 的隔离 ClassLoader（best-effort）。
            try {
                if (selfClassLoader != null) {
                    CoreClassLoaderRegistry.onCoreShutdown(selfClassLoader);
                }
            } catch (Throwable ignore) {
                // best-effort
            }
            // 入口额外清理（例如 container 关闭隔离 classloader 句柄）。
            try {
                if (extraFinallyAction != null) {
                    extraFinallyAction.run();
                }
            } catch (Throwable ignore) {
                // best-effort
            }
        }

        if (shutdownOkInfo != null && !shutdownOkInfo.trim().isEmpty()) {
            SleuthLogger.info(shutdownOkInfo);
        }
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
            BootstrapMonitoringConfigSync.syncFromProductionConfigBestEffort(cfg);
        } catch (Exception ignore) {
            // best-effort
        }
    }
}
