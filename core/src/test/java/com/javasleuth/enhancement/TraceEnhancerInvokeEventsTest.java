package com.javasleuth.core.enhancement;

import com.javasleuth.core.compiler.MemoryJavaCompiler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class TraceEnhancerInvokeEventsTest {

    @Test
    public void traceEnhancer_injectsInvokeAfterAndExceptionEvents() throws Exception {
        String className = "test.SampleTraceInvokeEvents";
        String src =
            "package test;" +
            "public class SampleTraceInvokeEvents {" +
            "  public String aOk(){ return ok(); }" +
            "  public String aBoom(){ return boom(); }" +
            "  public String ok(){ return \"ok\"; }" +
            "  public String boom(){ throw new RuntimeException(\"x\"); }" +
            "}";

        byte[] original = compile(className, src);
        TraceEnhancer enhancer = new TraceEnhancer(className, "a*", null, "trace-test-id");
        byte[] transformed = applyEnhancer(original, className, enhancer);

        AtomicInteger beforeCountInAOk = new AtomicInteger(0);
        AtomicInteger afterCountInAOk = new AtomicInteger(0);
        AtomicInteger exceptionCountInAOk = new AtomicInteger(0);

        ClassReader cr = new ClassReader(transformed);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"aOk".equals(name)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String desc, boolean itf) {
                        if ("com/javasleuth/bootstrap/spy/SleuthSpyAPI".equals(owner)) {
                            if ("atBeforeInvoke".equals(methodName)) {
                                beforeCountInAOk.incrementAndGet();
                            } else if ("atAfterInvoke".equals(methodName)) {
                                afterCountInAOk.incrementAndGet();
                            } else if ("atInvokeException".equals(methodName)) {
                                exceptionCountInAOk.incrementAndGet();
                            }
                        }
                        super.visitMethodInsn(opcode, owner, methodName, desc, itf);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);

        Assert.assertEquals(1, beforeCountInAOk.get());
        Assert.assertEquals(1, afterCountInAOk.get());
        Assert.assertEquals(1, exceptionCountInAOk.get());

        // Sanity: ensure bytecode remains verifiable and semantics preserved.
        Class<?> c = new ByteArrayClassLoader().define(className, transformed);
        Object inst = c.getDeclaredConstructor().newInstance();

        Method aOk = c.getMethod("aOk");
        Assert.assertEquals("ok", aOk.invoke(inst));

        Method aBoom = c.getMethod("aBoom");
        try {
            aBoom.invoke(inst);
            Assert.fail("expected RuntimeException");
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            Assert.assertTrue("expected RuntimeException, got: " + cause, cause instanceof RuntimeException);
        }
    }

    @Test
    public void traceEnhancer_skipsConstructorInvoke_toAvoidVerifyError() throws Exception {
        String className = "test.SampleTraceCtorInvoke";
        String src =
            "package test;" +
            "class Helper {" +
            "  Helper(){}" +
            "  int x(){ return 1; }" +
            "}" +
            "public class SampleTraceCtorInvoke {" +
            "  public int a(){ Helper h = new Helper(); return h.x(); }" +
            "}";

        Map<String, byte[]> compiled;
        try (MemoryJavaCompiler c = new MemoryJavaCompiler()) {
            MemoryJavaCompiler.CompilationResult r = c.compile(className, src);
            Assert.assertTrue("compile failed: " + r.getDiagnostics(), r.isSuccess());
            compiled = r.getCompiledClasses();
        }
        byte[] original = compiled.get(className);
        Assert.assertNotNull("no class bytes for " + className, original);
        TraceEnhancer enhancer = new TraceEnhancer(className, "a", null, "trace-test-id");
        byte[] transformed = applyEnhancer(original, className, enhancer);

        AtomicInteger beforeCountInA = new AtomicInteger(0);
        AtomicInteger afterCountInA = new AtomicInteger(0);
        AtomicInteger exceptionCountInA = new AtomicInteger(0);

        ClassReader cr = new ClassReader(transformed);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"a".equals(name)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String desc, boolean itf) {
                        if ("com/javasleuth/bootstrap/spy/SleuthSpyAPI".equals(owner)) {
                            if ("atBeforeInvoke".equals(methodName)) {
                                beforeCountInA.incrementAndGet();
                            } else if ("atAfterInvoke".equals(methodName)) {
                                afterCountInA.incrementAndGet();
                            } else if ("atInvokeException".equals(methodName)) {
                                exceptionCountInA.incrementAndGet();
                            }
                        }
                        super.visitMethodInsn(opcode, owner, methodName, desc, itf);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);

        Assert.assertEquals(1, beforeCountInA.get());
        Assert.assertEquals(1, afterCountInA.get());
        Assert.assertEquals(1, exceptionCountInA.get());

        // Verify bytecode remains verifiable (constructor invoke not wrapped).
        Class<?> c = new MapBackedClassLoader(compiled, className, transformed).loadClass(className);
        Object inst = c.getDeclaredConstructor().newInstance();
        Method a = c.getMethod("a");
        Object out = a.invoke(inst);
        Assert.assertEquals(1, ((Integer) out).intValue());
    }

    private byte[] compile(String className, String source) throws Exception {
        try (MemoryJavaCompiler c = new MemoryJavaCompiler()) {
            MemoryJavaCompiler.CompilationResult r = c.compile(className, source);
            Assert.assertTrue("compile failed: " + r.getDiagnostics(), r.isSuccess());
            Map<String, byte[]> classes = r.getCompiledClasses();
            byte[] bytes = classes.get(className);
            Assert.assertNotNull("no class bytes for " + className, bytes);
            return bytes;
        }
    }

    private byte[] applyEnhancer(byte[] classBytes, String className, ClassEnhancer enhancer) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        ClassVisitor cv = enhancer.createClassVisitor(cw, className);
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static final class MapBackedClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;
        private final String overrideName;
        private final byte[] overrideBytes;

        private MapBackedClassLoader(Map<String, byte[]> classes, String overrideName, byte[] overrideBytes) {
            super(MapBackedClassLoader.class.getClassLoader());
            this.classes = classes;
            this.overrideName = overrideName;
            this.overrideBytes = overrideBytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes;
            if (name != null && name.equals(overrideName) && overrideBytes != null) {
                bytes = overrideBytes;
            } else {
                bytes = classes != null ? classes.get(name) : null;
            }
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
