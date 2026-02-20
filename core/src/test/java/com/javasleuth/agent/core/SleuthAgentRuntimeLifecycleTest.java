package com.javasleuth.agent.core;

import com.javasleuth.agent.runtime.SleuthAgentRuntime;
import com.javasleuth.test.SleuthTestState;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import org.junit.After;
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

