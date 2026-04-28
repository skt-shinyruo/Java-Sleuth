package com.javasleuth.core.command;

import com.javasleuth.core.command.impl.ResetCommand;
import com.javasleuth.core.command.impl.StatusCommand;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class EnhancementSessionResetTest {
    @Test
    public void resetClosesEnhancementSessionsBeforeFallbackCleanup() {
        ProductionConfig config = ProductionConfig.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        EnhancementSessionRegistry enhancementRegistry = new EnhancementSessionRegistry();
        AtomicInteger closed = new AtomicInteger(0);
        enhancementRegistry.register(
            EnhancementSessionDescriptor.builder("watch-reset-test", EnhancementSessionKind.WATCH).build(),
            reason -> closed.incrementAndGet()
        );
        enhancementRegistry.register(
            EnhancementSessionDescriptor.builder("vmtool-reset-test", EnhancementSessionKind.VMTOOL).build(),
            reason -> closed.incrementAndGet()
        );

        ResetCommand command = new ResetCommand(
            fakeInstrumentation(),
            transformer,
            new JobManager(),
            new VmToolSessionRegistry(new SleuthSpyDispatcher()),
            enhancementRegistry
        );

        String result = command.execute(new String[]{"reset"});

        Assert.assertEquals(2, closed.get());
        Assert.assertEquals(0, enhancementRegistry.size());
        Assert.assertTrue(result.contains("enhancementSessions=2"));
        Assert.assertTrue(result.contains("enhancementClosed=2"));
        Assert.assertTrue(result.contains("enhancementFailed=0"));
    }

    @Test
    public void statusReportsEnhancementSessionCountsByKind() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        EnhancementSessionRegistry enhancementRegistry = new EnhancementSessionRegistry();
        enhancementRegistry.register(
            EnhancementSessionDescriptor.builder("trace-status-test", EnhancementSessionKind.TRACE).build(),
            reason -> {}
        );
        enhancementRegistry.register(
            EnhancementSessionDescriptor.builder("vmtool-status-test", EnhancementSessionKind.VMTOOL).build(),
            reason -> {}
        );

        PerformanceOptimizer optimizer = new PerformanceOptimizer(config);
        try {
            StatusCommand command = new StatusCommand(
                fakeInstrumentation(),
                new MetricsCollector(config),
                new SleuthClassFileTransformer(config),
                config,
                optimizer,
                new SleuthSpyDispatcher(),
                enhancementRegistry
            );

            String output = command.execute(new String[]{"status"});

            Assert.assertTrue(output.contains("-- Enhancement Sessions --"));
            Assert.assertTrue(output.contains("Active Sessions: 2"));
            Assert.assertTrue(output.contains("Trace: 1"));
            Assert.assertTrue(output.contains("VmTool: 1"));
        } finally {
            optimizer.close();
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
                if ("isModifiableClass".equals(name)) {
                    return true;
                }
                if ("removeTransformer".equals(name)) {
                    return true;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == Void.TYPE) {
                    return null;
                }
                if (returnType == Boolean.TYPE) {
                    return false;
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
