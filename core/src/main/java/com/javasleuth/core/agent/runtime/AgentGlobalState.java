package com.javasleuth.core.agent.runtime;

import com.javasleuth.bootstrap.monitor.MonitorInterceptor;
import com.javasleuth.bootstrap.monitor.StackInterceptor;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.monitor.TtInterceptor;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;

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
}
