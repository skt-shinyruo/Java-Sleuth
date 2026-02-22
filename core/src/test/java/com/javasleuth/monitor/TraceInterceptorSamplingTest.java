package com.javasleuth.bootstrap.monitor;

import com.javasleuth.bootstrap.data.TraceResult;
import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.Assert.*;

public class TraceInterceptorSamplingTest {

    @Test
    public void testInvocationLevelSamplingPairsEntryAndExit() {
        String traceId = "t-invocation-sampling";
        BlockingQueue<TraceResult> q = new LinkedBlockingQueue<>(100);

        TraceInterceptor.registerTrace(traceId, q, 0.0);
        try {
            TraceInterceptor.onMethodEntry(traceId, "C", "m", "()V", System.nanoTime());
            TraceInterceptor.onSubMethodCall(traceId, "T", "t", "()V", System.nanoTime());
            TraceInterceptor.onMethodExit(traceId, "C", "m", "()V", System.nanoTime(), 123, false);
            assertTrue(q.isEmpty());
        } finally {
            TraceInterceptor.unregisterTrace(traceId);
        }
    }

    @Test
    public void testInvocationLevelSamplingEmitsAllEventsWhenEnabled() {
        String traceId = "t-invocation-all";
        BlockingQueue<TraceResult> q = new LinkedBlockingQueue<>(100);

        TraceInterceptor.registerTrace(traceId, q, 1.0);
        try {
            TraceInterceptor.onMethodEntry(traceId, "C", "m", "()V", System.nanoTime());
            TraceInterceptor.onSubMethodCall(traceId, "T", "t", "()V", System.nanoTime());
            TraceInterceptor.onMethodExit(traceId, "C", "m", "()V", System.nanoTime(), 123, false);

            assertEquals(3, q.size());
        } finally {
            TraceInterceptor.unregisterTrace(traceId);
        }
    }
}
