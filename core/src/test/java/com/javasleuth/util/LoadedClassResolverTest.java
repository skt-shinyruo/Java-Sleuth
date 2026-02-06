package com.javasleuth.util;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

public class LoadedClassResolverTest {

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
    public void parseLoaderId_supportsBootstrapHexAndDecimal() {
        assertEquals(Integer.valueOf(0), LoadedClassResolver.parseLoaderId("bootstrap"));
        assertEquals(Integer.valueOf(0), LoadedClassResolver.parseLoaderId("null"));
        assertEquals(Integer.valueOf(0x12ab), LoadedClassResolver.parseLoaderId("0x12ab"));
        assertEquals(Integer.valueOf(1234), LoadedClassResolver.parseLoaderId("1234"));
        assertNull(LoadedClassResolver.parseLoaderId(""));
        assertNull(LoadedClassResolver.parseLoaderId("not-a-number"));
    }

    @Test
    public void resolveSingle_requiresLoaderWhenAmbiguous() throws Exception {
        byte[] bytes = simpleClassBytes("com/example/Dup");
        BytesClassLoader l1 = new BytesClassLoader(bytes);
        BytesClassLoader l2 = new BytesClassLoader(bytes);
        Class<?> c1 = l1.define("com.example.Dup");
        Class<?> c2 = l2.define("com.example.Dup");

        Instrumentation inst = fakeInstrumentation(new Class<?>[]{c1, c2});

        try {
            LoadedClassResolver.resolveSingle(inst, "com.example.Dup", null, true, 200, false);
            fail("Expected ResolutionException");
        } catch (LoadedClassResolver.ResolutionException e) {
            assertNotNull(e.getCandidates());
            assertEquals(2, e.getCandidates().size());
        }
    }

    @Test
    public void resolveSingle_canFilterByLoaderId() throws Exception {
        byte[] bytes = simpleClassBytes("com/example/Dup2");
        BytesClassLoader l1 = new BytesClassLoader(bytes);
        BytesClassLoader l2 = new BytesClassLoader(bytes);
        Class<?> c1 = l1.define("com.example.Dup2");
        Class<?> c2 = l2.define("com.example.Dup2");

        Instrumentation inst = fakeInstrumentation(new Class<?>[]{c1, c2});

        int id1 = LoadedClassResolver.loaderId(c1.getClassLoader());
        LoadedClassResolver.Candidate cand = LoadedClassResolver.resolveSingle(inst, "com.example.Dup2", id1, true, 200, false);
        assertNotNull(cand);
        assertEquals("com.example.Dup2", cand.getClassName());
        assertEquals(id1, cand.getLoaderId());
        assertSame(c1, cand.getClazz());
    }

    private static Instrumentation fakeInstrumentation(Class<?>[] loaded) {
        final Class<?>[] snapshot = loaded != null ? loaded.clone() : new Class<?>[0];
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[]{Instrumentation.class},
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
            });
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

