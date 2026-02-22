package com.javasleuth.test;

/**
 * Test-only global state reset helper.
 *
 * <p>Purpose: reduce cross-test pollution from static singletons / registries, especially when tests
 * involve lifecycle close/shutdown code paths.</p>
 */
public final class SleuthTestState {
    private SleuthTestState() {}

    public static void resetAll(String reason) {
        String r = reason != null ? reason : "test_reset";

        // Bootstrap-side registries (spy/bridge layer)
        try {
            com.javasleuth.bootstrap.monitor.VmToolInterceptor.clearAll();
        } catch (Exception ignore) {
            // ignore
        }
        try {
            com.javasleuth.bootstrap.monitor.WatchInterceptor.unregisterAllWatches();
        } catch (Exception ignore) {
            // ignore
        }
        try {
            com.javasleuth.bootstrap.monitor.TraceInterceptor.unregisterAllTraces();
        } catch (Exception ignore) {
            // ignore
        }
        try {
            com.javasleuth.bootstrap.monitor.MonitorInterceptor.unregisterAllMonitors();
        } catch (Exception ignore) {
            // ignore
        }
        try {
            com.javasleuth.bootstrap.monitor.TtInterceptor.unregisterAll();
        } catch (Exception ignore) {
            // ignore
        }
        try {
            com.javasleuth.bootstrap.monitor.StackInterceptor.unregisterAll();
        } catch (Exception ignore) {
            // ignore
        }

        // Security-related singletons
        try {
            com.javasleuth.foundation.security.AuthenticationManager.shutdownInstance();
        } catch (Exception ignore) {
            // ignore
        }
        try {
            com.javasleuth.foundation.security.DangerousCommandConfirmationManager.shutdownInstance();
        } catch (Exception ignore) {
            // ignore
        }

        // Runtime optimizers (MBeans / caches)
        try {
            com.javasleuth.foundation.util.PerformanceOptimizer.shutdown();
        } catch (Exception ignore) {
            // ignore
        }
        try {
            com.javasleuth.foundation.util.MemoryOptimizer.shutdownInstance();
        } catch (Exception ignore) {
            // ignore
        }
    }
}
