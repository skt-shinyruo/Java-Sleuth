package com.javasleuth.core.util;

import org.junit.Assert;
import org.junit.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public class ClassBytecodeResolverTest {
    @Test
    public void capturesCurrentBytesViaRetransformBeforeResourceFallback() {
        byte[] currentBytes = new byte[] {1, 2, 3, 4};
        AtomicReference<ClassFileTransformer> transformer = new AtomicReference<ClassFileTransformer>();
        Instrumentation inst = fakeInstrumentation(transformer, currentBytes);

        ClassBytecodeResolver.Result result = ClassBytecodeResolver.resolve(inst, Target.class);

        Assert.assertTrue(result.isCurrentJvmBytecode());
        Assert.assertEquals(ClassBytecodeResolver.Source.CURRENT_RETRANSFORM, result.getSource());
        Assert.assertTrue(Arrays.equals(currentBytes, result.getBytes()));
    }

    @Test
    public void fallsBackToResourceWhenRetransformUnsupported() {
        Instrumentation inst = (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("isRetransformClassesSupported".equals(name)) {
                    return false;
                }
                if ("isModifiableClass".equals(name)) {
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

        ClassBytecodeResolver.Result result = ClassBytecodeResolver.resolve(inst, Target.class);

        Assert.assertFalse(result.isCurrentJvmBytecode());
        Assert.assertEquals(ClassBytecodeResolver.Source.RESOURCE, result.getSource());
        Assert.assertNotNull(result.getBytes());
    }

    private static Instrumentation fakeInstrumentation(
        AtomicReference<ClassFileTransformer> transformer,
        byte[] currentBytes
    ) {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("isRetransformClassesSupported".equals(name)) {
                    return true;
                }
                if ("isModifiableClass".equals(name)) {
                    return true;
                }
                if ("addTransformer".equals(name)) {
                    transformer.set((ClassFileTransformer) args[0]);
                    return null;
                }
                if ("removeTransformer".equals(name)) {
                    transformer.set(null);
                    return true;
                }
                if ("retransformClasses".equals(name)) {
                    ClassFileTransformer t = transformer.get();
                    if (t != null) {
                        t.transform(
                            Target.class.getClassLoader(),
                            Target.class.getName().replace('.', '/'),
                            Target.class,
                            (ProtectionDomain) null,
                            currentBytes
                        );
                    }
                    return null;
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

    public static final class Target {
        public void work() {
        }
    }
}
