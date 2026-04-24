package com.javasleuth.core.command.impl;

import com.javasleuth.bootstrap.data.TraceResult;
import com.javasleuth.bootstrap.data.WatchResult;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.Assert;
import org.junit.Test;

public class StatusCommandListenerModeTest {

    @Test
    public void statusDoesNotPromoteLegacyRegistriesToPrimaryListenerCounts_whenDispatcherIsUnavailable() throws Exception {
        WatchInterceptor.unregisterAllWatches();
        TraceInterceptor.unregisterAllTraces();

        ProductionConfig config = ProductionConfig.createDefault();
        MetricsCollector metricsCollector = new MetricsCollector(config);
        try (PerformanceOptimizer performanceOptimizer = new PerformanceOptimizer(config)) {
            WatchInterceptor.registerWatch("legacy-watch", new LinkedBlockingQueue<WatchResult>(4));
            TraceInterceptor.registerTrace("legacy-trace", new LinkedBlockingQueue<TraceResult>(4));

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
            Assert.assertTrue(status.contains("Active Watches: unavailable"));
            Assert.assertTrue(status.contains("Active Traces: unavailable"));
            Assert.assertTrue(status.contains("Legacy Active Watches: 1"));
            Assert.assertTrue(status.contains("Legacy Active Traces: 1"));
        } finally {
            WatchInterceptor.unregisterAllWatches();
            TraceInterceptor.unregisterAllTraces();
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
