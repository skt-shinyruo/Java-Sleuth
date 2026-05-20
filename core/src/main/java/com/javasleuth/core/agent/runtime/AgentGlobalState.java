package com.javasleuth.core.agent.runtime;

import com.javasleuth.bootstrap.monitor.BootstrapMonitorConfigStore;
import com.javasleuth.bootstrap.monitor.MonitorInterceptor;
import com.javasleuth.bootstrap.monitor.StackInterceptor;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.monitor.TtInterceptor;
import com.javasleuth.bootstrap.monitor.VmToolInterceptor;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 全局静态状态收口点（bootstrap compatibility only）。
 *
 * <p>核心 watch/trace/monitor/stack/tt/vmtool track 状态由 {@code SleuthSpyDispatcher} 及
 * core 侧 session registry 持有。这里仅清理仍保留在 bootstrap 层的兼容性状态，避免
 * detach→re-attach/stop→restart/测试场景的隐性泄漏风险。</p>
 *
 * <p>注意：此处清理是 best-effort，不应影响 shutdown/reset 主流程。</p>
 */
public final class AgentGlobalState {
    private static final AtomicReference<CleanupResult> LAST_RUNTIME_CLEANUP = new AtomicReference<CleanupResult>();

    private AgentGlobalState() {}

    public static void recordRuntimeCleanupResult(CleanupResult result) {
        if (result != null) {
            LAST_RUNTIME_CLEANUP.set(result);
        }
    }

    public static CleanupResult getLastRuntimeCleanupResult() {
        return LAST_RUNTIME_CLEANUP.get();
    }

    public static void clearRuntimeCleanupResultForTests() {
        LAST_RUNTIME_CLEANUP.set(null);
    }

    public static void resetBootstrapAttachStateBestEffort() {
        clearBootstrapMonitorConfigStoreBestEffort();
        clearVmToolInterceptorBestEffort();
        resetInterceptorsBestEffort();
    }

    public static void resetInterceptorsBestEffort() {
        try {
            WatchInterceptor.resetForDetach();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            TraceInterceptor.resetForDetach();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            MonitorInterceptor.resetForDetach();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            TtInterceptor.resetForDetach();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            StackInterceptor.resetForDetach();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    public static void clearBootstrapMonitorConfigStoreBestEffort() {
        try {
            BootstrapMonitorConfigStore.clear();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    public static void clearVmToolInterceptorBestEffort() {
        try {
            VmToolInterceptor.clearAll();
        } catch (Exception ignore) {
            // best-effort
        }
    }
}
