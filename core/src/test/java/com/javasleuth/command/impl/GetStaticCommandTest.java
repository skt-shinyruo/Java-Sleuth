package com.javasleuth.core.command.impl;

import org.junit.Assert;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;

public class GetStaticCommandTest {
    public static final class Target {
        public static final String VALUE = "ok";
    }

    @Test
    public void readsLoadedClassWithoutClassForNameFallback() throws Exception {
        Instrumentation inst = fakeInstrumentation(Target.class);

        String out = new GetStaticCommand(inst).execute(new String[] {
            "getstatic",
            Target.class.getName(),
            "VALUE"
        });

        Assert.assertTrue(out, out.contains("VALUE = \"ok\""));
        Assert.assertTrue(out, out.contains("loaderId="));
    }

    @Test
    public void reportsMissingLoadedClassInsteadOfLoadingFromAgentClassLoader() throws Exception {
        Instrumentation inst = fakeInstrumentation();

        String out = new GetStaticCommand(inst).execute(new String[] {
            "getstatic",
            Target.class.getName(),
            "VALUE"
        });

        Assert.assertTrue(out, out.contains("未找到匹配的已加载类"));
        Assert.assertFalse(out, out.contains("VALUE = \"ok\""));
    }

    @Test
    public void rejectsInvalidLoaderOption() throws Exception {
        Instrumentation inst = fakeInstrumentation(Target.class);

        String out = new GetStaticCommand(inst).execute(new String[] {
            "getstatic",
            "--loader",
            "not-a-loader",
            Target.class.getName(),
            "VALUE"
        });

        Assert.assertTrue(out, out.contains("Invalid --loader value"));
    }

    private static Instrumentation fakeInstrumentation(Class<?>... loaded) {
        Class<?>[] snapshot = loaded != null ? loaded.clone() : new Class<?>[0];
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
                if ("getAllLoadedClasses".equals(method.getName())) {
                    return snapshot;
                }
                if ("isModifiableClass".equals(method.getName())) {
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
