package com.javasleuth.core.enhancement;

import com.javasleuth.bootstrap.data.WatchResult;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;
import com.javasleuth.core.compiler.MemoryJavaCompiler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

/**
 * 验证 watch 的“是否发送事件”与“是否采集值”解耦：
 * - --no-params 仍应产生 METHOD_ENTRY（但标记 parametersCaptured=false）
 * - --no-return 仍应产生正常 METHOD_EXIT（但标记 returnCaptured=false）
 */
public class WatchEnhancerCaptureFlagsTest {

    @Test
    public void watchEnhancer_emitsEntryEvenWhenNoParamsCapture() throws Exception {
        String watchId = "watch-test-no-params";
        BlockingQueue<WatchResult> q = new LinkedBlockingQueue<>(10);
        WatchInterceptor.registerWatch(watchId, q);
        try {
            String className = "test.SampleWatchNoParams";
            String src =
                "package test;" +
                "public class SampleWatchNoParams {" +
                "  public int inc(int x){ return x+1; }" +
                "}";

            byte[] original = compile(className, src);
            WatchEnhancer enhancer = new WatchEnhancer(className, "inc", null, false, true, true, watchId);
            byte[] transformed = applyEnhancer(original, className, enhancer);

            Class<?> c = new ByteArrayClassLoader().define(className, transformed);
            Object inst = c.getDeclaredConstructor().newInstance();

            Method inc = c.getMethod("inc", int.class);
            Object incOut = inc.invoke(inst, 1);
            Assert.assertEquals(2, ((Integer) incOut).intValue());

            WatchResult entry = q.poll(2, TimeUnit.SECONDS);
            Assert.assertNotNull("expected METHOD_ENTRY event", entry);
            Assert.assertEquals(WatchResult.EventType.METHOD_ENTRY, entry.getEventType());
            Assert.assertFalse("parametersCaptured should be false when --no-params", entry.isParametersCaptured());
            Assert.assertTrue("toString should reflect not-captured params",
                entry.toString().contains("<not captured>"));

            WatchResult exit = q.poll(2, TimeUnit.SECONDS);
            Assert.assertNotNull("expected METHOD_EXIT event", exit);
            Assert.assertEquals(WatchResult.EventType.METHOD_EXIT, exit.getEventType());
            Assert.assertTrue("returnCaptured should remain true", exit.isReturnCaptured());
            Assert.assertEquals(2, ((Integer) exit.getReturnValue()).intValue());

            Assert.assertNull("expected only 2 events", q.poll(200, TimeUnit.MILLISECONDS));
        } finally {
            WatchInterceptor.unregisterWatch(watchId);
        }
    }

    @Test
    public void watchEnhancer_emitsExitEvenWhenNoReturnCapture() throws Exception {
        String watchId = "watch-test-no-return";
        BlockingQueue<WatchResult> q = new LinkedBlockingQueue<>(10);
        WatchInterceptor.registerWatch(watchId, q);
        try {
            String className = "test.SampleWatchNoReturn";
            String src =
                "package test;" +
                "public class SampleWatchNoReturn {" +
                "  public String echo(String s){ return s; }" +
                "}";

            byte[] original = compile(className, src);
            WatchEnhancer enhancer = new WatchEnhancer(className, "echo", null, true, false, true, watchId);
            byte[] transformed = applyEnhancer(original, className, enhancer);

            Class<?> c = new ByteArrayClassLoader().define(className, transformed);
            Object inst = c.getDeclaredConstructor().newInstance();

            Method echo = c.getMethod("echo", String.class);
            Object echoOut = echo.invoke(inst, "hi");
            Assert.assertEquals("hi", echoOut);

            WatchResult entry = q.poll(2, TimeUnit.SECONDS);
            Assert.assertNotNull("expected METHOD_ENTRY event", entry);
            Assert.assertEquals(WatchResult.EventType.METHOD_ENTRY, entry.getEventType());
            Assert.assertTrue("parametersCaptured should remain true", entry.isParametersCaptured());

            WatchResult exit = q.poll(2, TimeUnit.SECONDS);
            Assert.assertNotNull("expected METHOD_EXIT event", exit);
            Assert.assertEquals(WatchResult.EventType.METHOD_EXIT, exit.getEventType());
            Assert.assertFalse("returnCaptured should be false when --no-return", exit.isReturnCaptured());
            Assert.assertTrue("toString should reflect not-captured return",
                exit.toString().contains("<not captured>"));

            Assert.assertNull("expected only 2 events", q.poll(200, TimeUnit.MILLISECONDS));
        } finally {
            WatchInterceptor.unregisterWatch(watchId);
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
