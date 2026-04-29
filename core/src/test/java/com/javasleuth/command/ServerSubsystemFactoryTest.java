package com.javasleuth.core.command;

import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.foundation.config.ProductionConfig;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.Assert;
import org.junit.Test;

public class ServerSubsystemFactoryTest {

    @Test
    public void create_usesConfiguredBoundedExecutorAndCallerRunsPolicy() {
        String oldCap = System.getProperty("sleuth.server.executor.queue.capacity");
        RuntimeServices services = null;
        CommandSubsystem command = null;
        ServerSubsystem server = null;
        try {
            System.setProperty("sleuth.server.executor.queue.capacity", "2");
            services = RuntimeServicesFactory.create(defaultRequest());
            command = CommandSubsystemFactory.create(services);
            server = ServerSubsystemFactory.create(services, command);

            Assert.assertNotNull(server.running);
            Assert.assertNotNull(server.commandCounter);
            Assert.assertTrue(server.clientExecutor.getQueue() instanceof LinkedBlockingQueue);
            Assert.assertEquals(2, server.clientExecutor.getQueue().remainingCapacity());
            Assert.assertTrue(server.clientExecutor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy);
            Assert.assertNotNull(server.sessionIndex);
            Assert.assertNotNull(server.clientHandler);
            Assert.assertNotNull(server.bootstrapper);
            Assert.assertNotNull(server.acceptor);
            Assert.assertNotNull(server.shutdownCoordinator);
        } finally {
            restoreProperty("sleuth.server.executor.queue.capacity", oldCap);
            if (server != null) {
                server.shutdownCoordinator.shutdownGracefully(null, 1);
            } else if (command != null) {
                final CommandSubsystem c = command;
                closeQuietly(new AutoCloseable() {
                    @Override
                    public void close() {
                        c.pipeline.shutdown();
                    }
                });
                closeQuietly(new AutoCloseable() {
                    @Override
                    public void close() {
                        c.registry.shutdown();
                    }
                });
            }
            if (services != null && server == null) {
                final RuntimeServices s = services;
                closeQuietly(new AutoCloseable() {
                    @Override
                    public void close() {
                        s.metricsCollector.shutdown();
                    }
                });
            }
            closeQuietly(ResourceCloser.forOwnedResources(services));
        }
    }

    private static CommandProcessorFactoryRequest defaultRequest() {
        ProductionConfig config = ProductionConfig.createDefault();
        return CommandProcessorFactoryRequest.builder(fakeInstrumentation(), new SleuthClassFileTransformer(config))
            .withConfig(config)
            .build();
    }

    private static void restoreProperty(String key, String oldValue) {
        if (oldValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, oldValue);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignore) {
            // best-effort test cleanup
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
