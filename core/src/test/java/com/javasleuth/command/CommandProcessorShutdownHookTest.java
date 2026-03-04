package com.javasleuth.core.command;

import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.test.SleuthTestState;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class CommandProcessorShutdownHookTest {

    @After
    public void tearDown() {
        SleuthTestState.resetAll("after_test");
    }

    @Test
    public void shutdownForDetachRemovesJvmShutdownHook() throws Exception {
        Instrumentation inst = fakeInstrumentation();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(ProductionConfig.createDefault());
        CommandProcessor processor = new CommandProcessor(inst, transformer);

        Field f = CommandProcessor.class.getDeclaredField("jvmShutdownHook");
        f.setAccessible(true);

        try {
            processor.addShutdownHook();
            Thread hook = (Thread) f.get(processor);
            Assert.assertNotNull("shutdown hook should be registered", hook);

            processor.shutdownForDetach();
            Thread after = (Thread) f.get(processor);
            Assert.assertNull("shutdown hook should be removed on detach shutdown", after);
        } finally {
            try {
                processor.shutdownForDetach();
            } catch (Exception ignore) {
                // ignore
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
