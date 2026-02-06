package com.javasleuth.enhancement;

import com.javasleuth.compiler.MemoryJavaCompiler;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

/**
 * 验证增强器不会破坏目标方法的返回值/异常语义（关键回归点）。
 */
public class EnhancerSemanticsTest {

    @Test
    public void watchEnhancer_doesNotBreakReturnOrException() throws Exception {
        String className = "test.SampleWatch";
        String src =
            "package test;" +
            "public class SampleWatch {" +
            "  public int inc(int x){ return x+1; }" +
            "  public String echo(String s){ return s; }" +
            "  public int throwIfNeg(int x){ if(x<0) throw new IllegalArgumentException(\"neg\"); return x; }" +
            "}";

        byte[] original = compile(className, src);
        WatchEnhancer enhancer = new WatchEnhancer(className, "*", null, true, true, true, "watch-test-id");
        byte[] transformed = applyEnhancer(original, className, enhancer);

        Class<?> c = new ByteArrayClassLoader().define(className, transformed);
        Object inst = c.getDeclaredConstructor().newInstance();

        Method inc = c.getMethod("inc", int.class);
        Object incOut = inc.invoke(inst, 1);
        Assert.assertEquals(2, ((Integer) incOut).intValue());

        Method echo = c.getMethod("echo", String.class);
        Object echoOut = echo.invoke(inst, "hi");
        Assert.assertEquals("hi", echoOut);

        Method throwIfNeg = c.getMethod("throwIfNeg", int.class);
        try {
            throwIfNeg.invoke(inst, -1);
            Assert.fail("expected IllegalArgumentException");
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable root = ite.getTargetException();
            Assert.assertTrue(root instanceof IllegalArgumentException);
            Assert.assertEquals("neg", root.getMessage());
        }
    }

    @Test
    public void ttEnhancer_doesNotBreakReturnOrException() throws Exception {
        String className = "test.SampleTt";
        String src =
            "package test;" +
            "public class SampleTt {" +
            "  public long add(long a,long b){ return a+b; }" +
            "  public void noop(){ }" +
            "  public String echo(String s){ return s; }" +
            "  public int throwIfNeg(int x){ if(x<0) throw new IllegalStateException(\"bad\"); return x; }" +
            "}";

        byte[] original = compile(className, src);
        TtEnhancer enhancer = new TtEnhancer(className, "*", null, "tt-test-id");
        byte[] transformed = applyEnhancer(original, className, enhancer);

        Class<?> c = new ByteArrayClassLoader().define(className, transformed);
        Object inst = c.getDeclaredConstructor().newInstance();

        Method add = c.getMethod("add", long.class, long.class);
        Object addOut = add.invoke(inst, 1L, 2L);
        Assert.assertEquals(3L, ((Long) addOut).longValue());

        Method echo = c.getMethod("echo", String.class);
        Object echoOut = echo.invoke(inst, "x");
        Assert.assertEquals("x", echoOut);

        Method noop = c.getMethod("noop");
        noop.invoke(inst);

        Method throwIfNeg = c.getMethod("throwIfNeg", int.class);
        try {
            throwIfNeg.invoke(inst, -1);
            Assert.fail("expected IllegalStateException");
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable root = ite.getTargetException();
            Assert.assertTrue(root instanceof IllegalStateException);
            Assert.assertEquals("bad", root.getMessage());
        }
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
}

