package com.javasleuth.monitor;

import com.javasleuth.data.StackTraceResult;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.Assert;
import org.junit.Test;

public class StackInterceptorTest {

    @Test
    public void onMethodEnter_publishesAndLimitsDepth() throws Exception {
        String id = "stack-test-1";
        BlockingQueue<StackTraceResult> q = new LinkedBlockingQueue<>(10);
        StackInterceptor.register(id, q);
        try {
            StackInterceptor.onMethodEnter(id, "com.example.Foo", "bar", "()V", 3);

            StackTraceResult r = q.poll();
            Assert.assertNotNull(r);
            Assert.assertEquals(id, r.getStackId());
            Assert.assertEquals("com.example.Foo", r.getClassName());
            Assert.assertEquals("bar", r.getMethodName());
            Assert.assertEquals("()V", r.getMethodDescriptor());
            Assert.assertNotNull(r.getThreadName());

            StackTraceElement[] st = r.getStackTrace();
            Assert.assertNotNull(st);
            Assert.assertTrue("stack depth should be <= 3, actual=" + st.length, st.length <= 3);
            Assert.assertTrue("stack depth should be > 0", st.length > 0);
        } finally {
            StackInterceptor.unregisterAll();
        }
    }

    @Test
    public void offerPolicy_whenQueueFull_shouldDropOrEvict() {
        String id = "stack-test-2";
        BlockingQueue<StackTraceResult> q = new LinkedBlockingQueue<>(1);
        StackInterceptor.register(id, q);
        try {
            long dropped0 = StackInterceptor.getDroppedEventCount();
            long evicted0 = StackInterceptor.getEvictedEventCount();

            StackInterceptor.onMethodEnter(id, "com.example.Foo", "bar", "()V", 1);
            StackInterceptor.onMethodEnter(id, "com.example.Foo", "bar", "()V", 1);

            long dropped1 = StackInterceptor.getDroppedEventCount();
            long evicted1 = StackInterceptor.getEvictedEventCount();
            Assert.assertTrue((dropped1 - dropped0) + (evicted1 - evicted0) >= 1);
        } finally {
            StackInterceptor.unregisterAll();
        }
    }
}

