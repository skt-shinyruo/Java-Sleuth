package com.javasleuth.enhancement;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.Assert.*;

public class SleuthClassFileTransformerFailurePolicyTest {

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
    public void failedTransform_doesNotRemoveEnhancer_andEntersCooldown() throws Exception {
        String oldCooldown = System.getProperty("sleuth.enhancement.failure.cooldown.ms");
        String oldLogInterval = System.getProperty("sleuth.enhancement.failure.log.interval.ms");

        try {
            System.setProperty("sleuth.enhancement.failure.cooldown.ms", "1000");
            System.setProperty("sleuth.enhancement.failure.log.interval.ms", "1000000");

            byte[] bytes = simpleClassBytes("com/example/Dup3");
            BytesClassLoader loader = new BytesClassLoader(bytes);
            Class<?> clazz = loader.define("com.example.Dup3");

            SleuthClassFileTransformer transformer = new SleuthClassFileTransformer();
            ClassEnhancer badEnhancer = new ClassEnhancer() {
                @Override
                public org.objectweb.asm.ClassVisitor createClassVisitor(org.objectweb.asm.ClassVisitor delegate, String className) {
                    throw new RuntimeException("boom");
                }

                @Override
                public String getDescription() {
                    return "bad";
                }
            };

            transformer.addEnhancer(clazz, badEnhancer);

            byte[] out1 = transformer.transform(loader, "com/example/Dup3", clazz, null, bytes);
            assertNull(out1);
            assertEquals(1L, transformer.getEnhancementFailureCount());
            assertEquals(1, transformer.getActiveEnhancersCount());
            assertTrue(transformer.getEnhancementCooldownCount() >= 1);

            long suppressedBefore = transformer.getEnhancementSuppressedCount();
            byte[] out2 = transformer.transform(loader, "com/example/Dup3", clazz, null, bytes);
            assertNull(out2);
            assertTrue(transformer.getEnhancementSuppressedCount() > suppressedBefore);
            // Cooldown skip should not add extra failure count.
            assertEquals(1L, transformer.getEnhancementFailureCount());
        } finally {
            setOrClearProperty("sleuth.enhancement.failure.cooldown.ms", oldCooldown);
            setOrClearProperty("sleuth.enhancement.failure.log.interval.ms", oldLogInterval);
        }
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
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

