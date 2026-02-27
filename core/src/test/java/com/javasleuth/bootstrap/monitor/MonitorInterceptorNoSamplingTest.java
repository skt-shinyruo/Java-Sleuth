package com.javasleuth.bootstrap.monitor;

import com.javasleuth.test.SleuthTestState;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class MonitorInterceptorNoSamplingTest {

    @After
    public void cleanup() {
        SleuthTestState.resetAll("MonitorInterceptorNoSamplingTest");
    }

    @Test
    public void shouldRecordStatsForEveryInvocation() {
        SleuthTestState.resetAll("MonitorInterceptorNoSamplingTest_before");

        String monitorId = "m-no-sampling";
        MonitorInterceptor.registerMonitor(monitorId);
        try {
            MonitorInterceptor.onMethodExit(monitorId, "C", "m", "()V", 100L, false);
            MonitorInterceptor.onMethodExit(monitorId, "C", "m", "()V", 100L, false);
            MonitorInterceptor.onMethodExit(monitorId, "C", "m", "()V", 200L, true);
            Map<String, MonitorInterceptor.MethodStatsSnapshot> snap = MonitorInterceptor.snapshot(monitorId);
            Assert.assertNotNull(snap);
            Assert.assertFalse("monitor should collect stats (no sampling)", snap.isEmpty());

            MonitorInterceptor.MethodStatsSnapshot stats = snap.get("C.m()V");
            Assert.assertNotNull("expected method key C.m()V in snapshot, got: " + snap.keySet(), stats);
            Assert.assertEquals(3, stats.getCount());
            Assert.assertEquals(1, stats.getExceptionCount());
        } finally {
            MonitorInterceptor.unregisterMonitor(monitorId);
        }
    }
}
