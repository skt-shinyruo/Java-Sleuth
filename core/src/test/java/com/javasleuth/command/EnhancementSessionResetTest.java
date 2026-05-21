package com.javasleuth.core.command;

import com.javasleuth.core.command.impl.ResetCommand;
import com.javasleuth.core.command.impl.StatusCommand;
import com.javasleuth.core.enhancement.ClassEnhancer;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class EnhancementSessionResetTest {
    private static final class BytesClassLoader extends ClassLoader {
        private final byte[] bytes;

        private BytesClassLoader(byte[] bytes) {
            super(null);
            this.bytes = bytes;
        }

        private Class<?> define(String name) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Test
    public void resetClosesEnhancementSessionsBeforeFallbackCleanup() {
        ProductionConfig config = ProductionConfig.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        EnhancementSessionRegistry enhancementRegistry = new EnhancementSessionRegistry();
        AtomicInteger closed = new AtomicInteger(0);
        enhancementRegistry.register(
            EnhancementSessionDescriptor.builder("watch-reset-test", EnhancementSessionKind.WATCH).build(),
            reason -> closed.incrementAndGet()
        );
        enhancementRegistry.register(
            EnhancementSessionDescriptor.builder("vmtool-reset-test", EnhancementSessionKind.VMTOOL).build(),
            reason -> closed.incrementAndGet()
        );

        ResetCommand command = new ResetCommand(
            fakeInstrumentation(),
            transformer,
            new JobManager(),
            new VmToolSessionRegistry(new SleuthSpyDispatcher()),
            enhancementRegistry
        );

        String result = command.execute(new String[]{"reset"});

        Assert.assertEquals(2, closed.get());
        Assert.assertEquals(0, enhancementRegistry.size());
        Assert.assertTrue(result.contains("enhancementSessions=2"));
        Assert.assertTrue(result.contains("enhancementClosed=2"));
        Assert.assertTrue(result.contains("enhancementFailed=0"));
    }

    @Test
    public void statusReportsEnhancementSessionCountsByKind() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        EnhancementSessionRegistry enhancementRegistry = new EnhancementSessionRegistry();
        enhancementRegistry.register(
            EnhancementSessionDescriptor.builder("trace-status-test", EnhancementSessionKind.TRACE).build(),
            reason -> {}
        );
        enhancementRegistry.register(
            EnhancementSessionDescriptor.builder("vmtool-status-test", EnhancementSessionKind.VMTOOL).build(),
            reason -> {}
        );

        PerformanceOptimizer optimizer = new PerformanceOptimizer(config);
        try {
            StatusCommand command = new StatusCommand(
                fakeInstrumentation(),
                new MetricsCollector(config),
                new SleuthClassFileTransformer(config),
                config,
                optimizer,
                new SleuthSpyDispatcher(),
                enhancementRegistry
            );

            String output = command.execute(new String[]{"status"});

            Assert.assertTrue(output.contains("-- Enhancement Sessions --"));
            Assert.assertTrue(output.contains("Active Sessions: 2"));
            Assert.assertTrue(output.contains("Trace: 1"));
            Assert.assertTrue(output.contains("VmTool: 1"));
        } finally {
            optimizer.close();
        }
    }

    @Test
    public void resetRetransformsOnlyEnhancedLoaderInstanceForDuplicateClassNames() throws Exception {
        byte[] bytes = simpleClassBytes("com/example/DupResetTarget");
        BytesClassLoader loader1 = new BytesClassLoader(bytes);
        BytesClassLoader loader2 = new BytesClassLoader(bytes);
        Class<?> enhancedClass = loader1.define("com.example.DupResetTarget");
        Class<?> sameNameOtherLoader = loader2.define("com.example.DupResetTarget");

        ProductionConfig config = ProductionConfig.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        transformer.addEnhancer(enhancedClass, passthroughEnhancer());
        List<Class<?>> retransformed = new ArrayList<Class<?>>();

        ResetCommand command = new ResetCommand(
            fakeInstrumentation(new Class<?>[] {enhancedClass, sameNameOtherLoader}, retransformed),
            transformer,
            new JobManager(),
            new VmToolSessionRegistry(new SleuthSpyDispatcher())
        );

        String result = command.execute(new String[] {"reset"});

        Assert.assertEquals(1, retransformed.size());
        Assert.assertSame(enhancedClass, retransformed.get(0));
        Assert.assertTrue(result, result.contains("enhancedClasses=1"));
        Assert.assertTrue(result, result.contains("retransformed=1"));
    }

    private static Instrumentation fakeInstrumentation() {
        return fakeInstrumentation(new Class<?>[0], null);
    }

    private static Instrumentation fakeInstrumentation(Class<?>[] loadedClasses, List<Class<?>> retransformed) {
        final Class<?>[] loaded = loadedClasses != null ? loadedClasses.clone() : new Class<?>[0];
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getAllLoadedClasses".equals(name)) {
                    return loaded;
                }
                if ("isModifiableClass".equals(name)) {
                    return true;
                }
                if ("retransformClasses".equals(name)) {
                    if (retransformed != null && args != null && args.length > 0 && args[0] instanceof Class<?>[]) {
                        for (Class<?> clazz : (Class<?>[]) args[0]) {
                            retransformed.add(clazz);
                        }
                    }
                    return null;
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

    private static ClassEnhancer passthroughEnhancer() {
        return new ClassEnhancer() {
            @Override
            public ClassVisitor createClassVisitor(ClassVisitor delegate, String className) {
                return delegate;
            }

            @Override
            public String getDescription() {
                return "reset-test";
            }
        };
    }

    private static byte[] simpleClassBytes(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
