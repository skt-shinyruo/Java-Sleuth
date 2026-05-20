package com.javasleuth.core.command.impl;

import com.javasleuth.bootstrap.agent.AgentLifecycle;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import org.junit.Assert;
import org.junit.Test;

public class StatusCommandListenerModeTest {

    @Test
    public void statusDisplaysNormalizedValuesForInvalidExplicitSystemProperties() throws Exception {
        System.setProperty("sleuth.server.port", "70000");
        System.setProperty("sleuth.security.input.validation", "maybe");
        System.setProperty("sleuth.monitoring.metrics.enabled", "maybe");

        ProductionConfig config = null;
        MetricsCollector metricsCollector = null;
        try {
            config = ProductionConfig.createDefault();
            metricsCollector = new MetricsCollector(config);
            try (PerformanceOptimizer performanceOptimizer = new PerformanceOptimizer(config)) {
                StatusCommand command = new StatusCommand(
                    fakeInstrumentation(),
                    metricsCollector,
                    null,
                    config,
                    performanceOptimizer,
                    null
                );

                String status = command.execute(new String[]{"status"});

                Assert.assertTrue(status.contains("Server Port: 3658"));
                Assert.assertTrue(status.contains("Input Validation: ENABLED"));
                Assert.assertTrue(status.contains("Metrics Collection: ENABLED"));
                Assert.assertFalse(status.contains("Server Port: 70000"));
            }
        } finally {
            System.clearProperty("sleuth.server.port");
            System.clearProperty("sleuth.security.input.validation");
            System.clearProperty("sleuth.monitoring.metrics.enabled");
            if (metricsCollector != null) {
                metricsCollector.shutdown();
            }
        }
    }

    @Test
    public void statusDisplaysValidRuntimeOverrides() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("server.port", "12345");
        config.setRuntimeConfig("security.input.validation", "false");
        config.setRuntimeConfig("monitoring.metrics.enabled", "false");

        MetricsCollector metricsCollector = new MetricsCollector(config);
        try (PerformanceOptimizer performanceOptimizer = new PerformanceOptimizer(config)) {
            StatusCommand command = new StatusCommand(
                fakeInstrumentation(),
                metricsCollector,
                null,
                config,
                performanceOptimizer,
                null
            );

            String status = command.execute(new String[]{"status"});

            Assert.assertTrue(status.contains("Server Port: 12345"));
            Assert.assertTrue(status.contains("Input Validation: DISABLED"));
            Assert.assertTrue(status.contains("Metrics Collection: DISABLED"));
        } finally {
            metricsCollector.shutdown();
        }
    }

    @Test
    public void statusDoesNotExposeLegacyRegistryCounts_whenDispatcherIsUnavailable() throws Exception {
        WatchInterceptor.unregisterAllWatches();
        TraceInterceptor.unregisterAllTraces();

        ProductionConfig config = ProductionConfig.createDefault();
        MetricsCollector metricsCollector = new MetricsCollector(config);
        try (PerformanceOptimizer performanceOptimizer = new PerformanceOptimizer(config)) {
            StatusCommand command = new StatusCommand(
                fakeInstrumentation(),
                metricsCollector,
                null,
                config,
                performanceOptimizer,
                null
            );

            String status = command.execute(new String[]{"status"});

            Assert.assertTrue(status.contains("Listener Runtime Installed: false"));
            Assert.assertTrue(status.contains("Watch Listeners: unavailable"));
            Assert.assertTrue(status.contains("Trace Listeners: unavailable"));
            Assert.assertFalse(status.contains("Active Watches:"));
            Assert.assertFalse(status.contains("Active Traces:"));
            Assert.assertFalse(status.contains("Legacy Active Watches:"));
            Assert.assertFalse(status.contains("Legacy Active Traces:"));
            Assert.assertFalse(status.contains("Legacy Watch Published:"));
        } finally {
            WatchInterceptor.unregisterAllWatches();
            TraceInterceptor.unregisterAllTraces();
            metricsCollector.shutdown();
        }
    }

    @Test
    public void statusSurfacesLifecycleCleanupDegradedState() throws Exception {
        AgentLifecycle.clearCleanupStatusForTests();
        AgentLifecycle.recordRuntimeCleanupResult("PARTIAL", "runtime cleanup failed: rollback boom");

        ProductionConfig config = ProductionConfig.createDefault();
        MetricsCollector metricsCollector = new MetricsCollector(config);
        try (PerformanceOptimizer performanceOptimizer = new PerformanceOptimizer(config)) {
            StatusCommand command = new StatusCommand(
                fakeInstrumentation(),
                metricsCollector,
                null,
                config,
                performanceOptimizer,
                null
            );

            String status = command.execute(new String[]{"status"});

            Assert.assertTrue(status.contains("Lifecycle Cleanup: DEGRADED"));
            Assert.assertTrue(status.contains("partial cleanup"));
            Assert.assertTrue(status.contains("rollback boom"));
            Assert.assertTrue(status.contains("Ready for Production: NO"));
        } finally {
            AgentLifecycle.clearCleanupStatusForTests();
            metricsCollector.shutdown();
        }
    }

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getAllLoadedClasses".equals(name)) {
                    return new Class<?>[0];
                }
                if ("isRedefineClassesSupported".equals(name)
                    || "isRetransformClassesSupported".equals(name)
                    || "isNativeMethodPrefixSupported".equals(name)) {
                    return Boolean.TRUE;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == Void.TYPE) {
                    return null;
                }
                if (returnType == Boolean.TYPE) {
                    return Boolean.FALSE;
                }
                if (returnType == Integer.TYPE) {
                    return 0;
                }
                if (returnType == Long.TYPE) {
                    return 0L;
                }
                if (returnType.isArray()) {
                    return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                }
                return null;
            }
        );
    }
}
