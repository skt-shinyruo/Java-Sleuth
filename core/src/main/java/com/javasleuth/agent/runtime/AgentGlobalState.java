package com.javasleuth.agent.runtime;

import com.javasleuth.monitor.MonitorInterceptor;
import com.javasleuth.monitor.StackInterceptor;
import com.javasleuth.monitor.TraceInterceptor;
import com.javasleuth.monitor.TtInterceptor;
import com.javasleuth.monitor.WatchInterceptor;

/**
 * 全局静态状态收口点（bridge-only）。
 *
 * <p>该类用于集中管理 bootstrap 侧 interceptor 的静态注册表清理逻辑，避免在多处散落调用，降低
 * detach→re-attach/stop→restart/测试场景的隐性泄漏风险。</p>
 *
 * <p>注意：此处清理是 best-effort，不应影响 shutdown/reset 主流程。</p>
 */
public final class AgentGlobalState {
    private AgentGlobalState() {}

    public static void resetInterceptorsBestEffort() {
        try {
            WatchInterceptor.unregisterAllWatches();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            TraceInterceptor.unregisterAllTraces();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            MonitorInterceptor.unregisterAllMonitors();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            TtInterceptor.unregisterAll();
        } catch (Exception ignore) {
            // best-effort
        }
        try {
            StackInterceptor.unregisterAll();
        } catch (Exception ignore) {
            // best-effort
        }
    }
}

