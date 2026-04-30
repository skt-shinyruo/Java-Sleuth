package com.javasleuth.core.command;

import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.foundation.config.ProductionConfig;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import org.junit.Assert;
import org.junit.Test;

public class CommandProcessorFactoryRequestTest {

    @Test
    public void create_usesRequestAsSingleCompositionInput() {
        ProductionConfig config = ProductionConfig.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        Instrumentation instrumentation = fakeInstrumentation();

        CommandProcessorFactoryRequest request = CommandProcessorFactoryRequest.builder(instrumentation, transformer)
            .withConfig(config)
            .withMetricsCollector(new com.javasleuth.core.monitoring.MetricsCollector(config))
            .build();

        CommandProcessor processor = CommandProcessorFactory.create(request);
        try {
            Assert.assertNotNull(processor);
            Assert.assertSame(request.getMetricsCollector(), processor.getMetricsCollector());
        } finally {
            try {
                processor.shutdownGracefully(1);
            } catch (Exception ignore) {
                // best-effort for test cleanup
            }
        }
    }

    @Test
    public void createComponents_usesRequestAsSingleCompositionInput() {
        ProductionConfig config = ProductionConfig.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        Instrumentation instrumentation = fakeInstrumentation();

        CommandProcessorFactoryRequest request = CommandProcessorFactoryRequest.builder(instrumentation, transformer)
            .withConfig(config)
            .withMetricsCollector(new com.javasleuth.core.monitoring.MetricsCollector(config))
            .build();

        CommandProcessorComponents components = CommandProcessorFactory.createComponents(request);
        try {
            Assert.assertSame(request.getMetricsCollector(), components.getMetricsCollector());
            Assert.assertNotNull(components.getRegistry());
            Assert.assertNotNull(components.getPipeline());
            Assert.assertNotNull(components.getClientHandler());
            Assert.assertNotNull(components.getShutdownCoordinator());
        } finally {
            try {
                components.getShutdownCoordinator().shutdownGracefully(null, 1);
            } catch (Exception ignore) {
                // best-effort for test cleanup
            }
            try {
                AutoCloseable ownedResources = components.getOwnedResources();
                if (ownedResources != null) {
                    ownedResources.close();
                }
            } catch (Exception ignore) {
                // best-effort for test cleanup
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    public void deprecatedCreateOverload_remainsCompatibilityWrapper() {
        ProductionConfig config = ProductionConfig.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        Instrumentation instrumentation = fakeInstrumentation();
        com.javasleuth.core.monitoring.MetricsCollector metrics =
            new com.javasleuth.core.monitoring.MetricsCollector(config);

        CommandProcessor processor = CommandProcessorFactory.create(
            instrumentation,
            transformer,
            null,
            config,
            null,
            null,
            null,
            null,
            null,
            metrics
        );
        try {
            Assert.assertSame(metrics, processor.getMetricsCollector());
        } finally {
            try {
                processor.shutdownGracefully(1);
            } catch (Exception ignore) {
                // best-effort for test cleanup
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    public void deprecatedCreateComponentsOverload_remainsCompatibilityWrapper() {
        ProductionConfig config = ProductionConfig.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        Instrumentation instrumentation = fakeInstrumentation();
        com.javasleuth.core.monitoring.MetricsCollector metrics =
            new com.javasleuth.core.monitoring.MetricsCollector(config);
        JobManager jobManager = new JobManager();

        CommandProcessorComponents components = CommandProcessorFactory.createComponents(
            instrumentation,
            transformer,
            null,
            config,
            null,
            null,
            null,
            null,
            null,
            metrics,
            jobManager,
            null
        );
        try {
            Assert.assertSame(metrics, components.getMetricsCollector());
        } finally {
            try {
                components.getShutdownCoordinator().shutdownGracefully(null, 1);
            } catch (Exception ignore) {
                // best-effort for test cleanup
            }
            try {
                AutoCloseable ownedResources = components.getOwnedResources();
                if (ownedResources != null) {
                    ownedResources.close();
                }
            } catch (Exception ignore) {
                // best-effort for test cleanup
            }
        }
    }

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
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
