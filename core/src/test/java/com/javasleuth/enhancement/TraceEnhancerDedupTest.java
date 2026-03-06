package com.javasleuth.core.enhancement;

import com.javasleuth.core.compiler.MemoryJavaCompiler;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 验证 trace enhancer 在同一类内“可被 trace 的方法调用”不会产生重复的 SUB_METHOD_CALL。
 */
public class TraceEnhancerDedupTest {

    @Test
    public void traceEnhancer_skipsSubCallForTracedCallee() throws Exception {
        String className = "test.SampleTrace";
        String src =
            "package test;" +
            "public class SampleTrace {" +
            "  public int b(int x){ return x+1; }" +
            "  public int a(int x){ return b(x); }" +
            "}";

        byte[] original = compile(className, src);
        TraceEnhancer enhancer = new TraceEnhancer(className, "*", null, "trace-test-id");
        byte[] transformed = applyEnhancer(original, className, enhancer);

        AtomicInteger subCallCountInA = new AtomicInteger(0);
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
                        if ("com/javasleuth/bootstrap/spy/SleuthSpyAPI".equals(owner) && "atBeforeInvoke".equals(methodName)) {
                            subCallCountInA.incrementAndGet();
                        }
                        super.visitMethodInsn(opcode, owner, methodName, desc, itf);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);

        Assert.assertEquals(0, subCallCountInA.get());
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
}
