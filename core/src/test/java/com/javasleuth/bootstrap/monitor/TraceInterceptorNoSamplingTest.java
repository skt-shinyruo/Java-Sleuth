package com.javasleuth.bootstrap.monitor;

import com.javasleuth.bootstrap.data.TraceResult;
import com.javasleuth.test.SleuthTestState;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class TraceInterceptorNoSamplingTest {

    @After
    public void cleanup() {
        SleuthTestState.resetAll("TraceInterceptorNoSamplingTest");
    }

    @Test
    public void shouldEmitEventsForEveryInvocation() {
        SleuthTestState.resetAll("TraceInterceptorNoSamplingTest_before");

        String traceId = "t-no-sampling";
        BlockingQueue<TraceResult> q = new LinkedBlockingQueue<>(100);
        TraceInterceptor.registerTrace(traceId, q);
        try {
            long start = System.nanoTime();
            TraceInterceptor.onMethodEntry(traceId, "C", "m", "()V", start);
            TraceInterceptor.onSubMethodCall(traceId, "T", "t", "()V", System.nanoTime());
            TraceInterceptor.onMethodExit(traceId, "C", "m", "()V", start, 123L, false);

            Assert.assertEquals("trace should emit one entry+subcall+exit", 3, q.size());

            long start2 = System.nanoTime();
            TraceInterceptor.onMethodEntry(traceId, "C", "m2", "()V", start2);
            TraceInterceptor.onSubMethodCall(traceId, "T", "t2", "()V", System.nanoTime());
            TraceInterceptor.onMethodExit(traceId, "C", "m2", "()V", start2, 456L, false);

            Assert.assertEquals("trace should emit events for every invocation (no sampling)", 6, q.size());
        } finally {
            TraceInterceptor.unregisterTrace(traceId);
        }
    }
}
