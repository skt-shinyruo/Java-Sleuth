package com.javasleuth.command.impl;

import com.javasleuth.command.JobManager;
import com.javasleuth.data.TtRecord;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.monitor.TtInterceptor;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.Assert;
import org.junit.Test;

public class TtCommandReplayTemplateTest {

    private static Instrumentation dummyInstrumentation() {
        return (Instrumentation)
            Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[] { Instrumentation.class },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        Class<?> returnType = method.getReturnType();
                        if (returnType == void.class) {
                            return null;
                        }
                        if (returnType == boolean.class) {
                            return false;
                        }
                        if (returnType == int.class) {
                            return 0;
                        }
                        if (returnType == long.class) {
                            return 0L;
                        }
                        if (returnType.isArray()) {
                            return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                        }
                        return null;
                    }
                }
            );
    }

    @Test
    public void replayTemplateShouldNotContainTodoPlaceholder() throws Exception {
        TtInterceptor.clear();
        try {
            String ttId = "tt_test";
            TtInterceptor.register(ttId, new LinkedBlockingQueue<TtRecord>());

            TtInterceptor.onMethodExit(
                ttId,
                "com.example.Foo",
                "bar",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                new Object[] { "x", Integer.valueOf(1) },
                "y",
                System.nanoTime(),
                1000L
            );

            List<TtRecord> records = TtInterceptor.list(1);
            Assert.assertNotNull(records);
            Assert.assertFalse(records.isEmpty());

            long id = records.get(0).getRecordId();
            TtCommand cmd = new TtCommand(
                dummyInstrumentation(),
                new SleuthClassFileTransformer(ProductionConfig.getInstance()),
                ProductionConfig.getInstance(),
                new JobManager()
            );
            String out = cmd.execute(new String[] { "tt", "replay", String.valueOf(id) });

            Assert.assertNotNull(out);
            Assert.assertFalse(out.contains("TODO"));
            Assert.assertTrue(out.contains("无法自动定位实例"));
        } finally {
            TtInterceptor.clear();
            TtInterceptor.unregisterAll();
        }
    }
}
