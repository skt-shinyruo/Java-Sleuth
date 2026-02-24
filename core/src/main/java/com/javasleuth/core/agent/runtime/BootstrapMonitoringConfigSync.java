package com.javasleuth.core.agent.runtime;

import com.javasleuth.bootstrap.monitor.BootstrapMonitorConfigStore;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.MonitoringConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;

/**
 * Bootstrap 监控配置同步桥（core -> bootstrap）。
 *
 * <p>目的：bootstrap 拦截器不能依赖完整的 config 栈，因此通过 {@link BootstrapMonitorConfigStore}
 * 缓存 attach 级别的“effective monitoring config”，避免运行时频繁读取/解析 sysprop，并确保
 * {@code config set/remove/clear} 的 runtime overrides 能同步生效。</p>
 *
 * <p>注意：该 Store 必须在 detach/shutdown 时清理（由入口编排负责）。</p>
 */
public final class BootstrapMonitoringConfigSync {
    private BootstrapMonitoringConfigSync() {}

    /**
     * 从 {@link ProductionConfig} 生成一致性快照并同步到 bootstrap Store（best-effort）。
     */
    public static void syncFromProductionConfigBestEffort(ProductionConfig config) {
        if (config == null) {
            return;
        }
        try {
            syncFromConfigViewBestEffort(config.snapshot());
        } catch (Exception ignore) {
            // best-effort
        }
    }

    /**
     * 从 {@link ConfigView} 同步 bootstrap monitoring 配置（best-effort）。
     */
    public static void syncFromConfigViewBestEffort(ConfigView config) {
        if (config == null) {
            return;
        }
        try {
            SleuthConfig typed = SleuthConfigParser.parse(config);
            MonitoringConfig monitoring = typed.monitoring();

            BootstrapMonitorConfigStore.setWatchDropOnFull(monitoring.isWatchDropOnFull());
            BootstrapMonitorConfigStore.setTraceDropOnFull(monitoring.isTraceDropOnFull());
            BootstrapMonitorConfigStore.setTraceSampleRate(monitoring.getTraceSampleRate());
            BootstrapMonitorConfigStore.setMonitorSampleRate(monitoring.getMonitorSampleRate());
        } catch (Exception ignore) {
            // best-effort
        }
    }
}

