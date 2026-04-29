package com.javasleuth.core.command;

import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.foundation.config.ProductionConfig;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import org.junit.Assert;
import org.junit.Test;

public class CommandSubsystemFactoryTest {

    @Test
    public void create_buildsRegistryWithBuiltinHelpAndPipeline() {
        RuntimeServices services = RuntimeServicesFactory.create(defaultRequest());
        CommandSubsystem subsystem = null;
        try {
            subsystem = CommandSubsystemFactory.create(services);

            Assert.assertNotNull(subsystem.registry);
            Assert.assertNotNull(subsystem.pipeline);
            Assert.assertTrue(subsystem.registry.getCommandMap().containsKey("help"));
        } finally {
            if (subsystem != null) {
                final CommandSubsystem s = subsystem;
                closeQuietly(new AutoCloseable() {
                    @Override
                    public void close() {
                        s.pipeline.shutdown();
                    }
                });
                closeQuietly(new AutoCloseable() {
                    @Override
                    public void close() {
                        s.registry.shutdown();
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
