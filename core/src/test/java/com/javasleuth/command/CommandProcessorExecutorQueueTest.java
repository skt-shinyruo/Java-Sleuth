package com.javasleuth.core.command;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import org.junit.Assert;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

public class CommandProcessorExecutorQueueTest {

    @Test
    public void testClientExecutorQueueIsBoundedAndCallerRunsEnabled() throws Exception {
        String oldCap = System.getProperty("sleuth.server.executor.queue.capacity");
        try {
            System.setProperty("sleuth.server.executor.queue.capacity", "1");
            Instrumentation inst = fakeInstrumentation();
            SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(ProductionConfig.createDefault());

            CommandProcessor processor = new CommandProcessor(inst, transformer);

            ThreadPoolExecutor executor = (ThreadPoolExecutor) readField(processor, "clientExecutor");
            Assert.assertNotNull(executor);
            Assert.assertTrue(executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy);

            BlockingQueue<Runnable> queue = executor.getQueue();
            Assert.assertNotNull(queue);
            Assert.assertTrue(queue instanceof LinkedBlockingQueue);
            Assert.assertEquals(1, queue.remainingCapacity());
        } finally {
            if (oldCap == null) {
                System.clearProperty("sleuth.server.executor.queue.capacity");
            } else {
                System.setProperty("sleuth.server.executor.queue.capacity", oldCap);
            }
        }
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, args) -> {
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
            });
    }
}
