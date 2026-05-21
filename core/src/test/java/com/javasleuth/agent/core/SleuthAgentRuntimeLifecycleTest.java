package com.javasleuth.core.agent.core;

import com.javasleuth.core.agent.runtime.AgentGlobalState;
import com.javasleuth.core.agent.runtime.CleanupResult;
import com.javasleuth.core.agent.runtime.SleuthAgentRuntime;
import com.javasleuth.test.SleuthTestState;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class SleuthAgentRuntimeLifecycleTest {

    @After
    public void tearDown() {
        SleuthTestState.resetAll("after_test");
    }

    @Test
    public void closeIsIdempotent() {
        Instrumentation inst = fakeInstrumentation();
        SleuthAgentRuntime runtime = SleuthAgentRuntime.create(inst, () -> {});
        runtime.close();
        runtime.close();
    }

    @Test
    public void startFailsFastAndCleansUpWhenCommandProcessorRejectsBindAddress() {
        String oldBind = System.getProperty("sleuth.server.bind.address");
        AtomicInteger removeTransformerAttempts = new AtomicInteger(0);

        try {
            System.setProperty("sleuth.server.bind.address", "0.0.0.0");

            try {
                SleuthAgentRuntime.start(fakeInstrumentation(removeTransformerAttempts), () -> {});
                Assert.fail("Expected command processor startup failure");
            } catch (IllegalStateException expected) {
                Assert.assertTrue(expected.getMessage().contains("command processor"));
            }

            Assert.assertEquals(1, removeTransformerAttempts.get());
            CleanupResult cleanup = AgentGlobalState.getLastRuntimeCleanupResult();
            Assert.assertNotNull(cleanup);
        } finally {
            setOrClearProperty("sleuth.server.bind.address", oldBind);
        }
    }

    private static Instrumentation fakeInstrumentation() {
        return fakeInstrumentation(null);
    }

    private static Instrumentation fakeInstrumentation(AtomicInteger removeTransformerAttempts) {
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
                    if (removeTransformerAttempts != null) {
                        removeTransformerAttempts.incrementAndGet();
                    }
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

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
