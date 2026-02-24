package com.javasleuth.bootstrap.monitor;

import com.javasleuth.bootstrap.data.StackTraceResult;
import com.javasleuth.bootstrap.data.TtRecord;
import com.javasleuth.bootstrap.data.TraceResult;
import com.javasleuth.bootstrap.data.WatchResult;
import com.javasleuth.test.SleuthTestState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class InterceptorDetachResetTest {

    @After
    public void tearDown() {
        SleuthTestState.resetAll("after_test");
    }

    @Test
    public void resetForDetach_clearsWatchQueuesAndMetrics() {
        String id = "watch-detach-reset";
        BlockingQueue<WatchResult> q = new LinkedBlockingQueue<>(10);
        WatchInterceptor.registerWatch(id, q);
        try {
            WatchInterceptor.onMethodEntry(id, "C", "m", "()V", new Object[] {"x"}, System.nanoTime());
            Assert.assertTrue("expected watch queue to receive events", q.size() > 0);
            Assert.assertEquals(1, WatchInterceptor.getActiveWatchCount());
            Assert.assertTrue(WatchInterceptor.getPublishedEventCount() > 0);
        } finally {
            WatchInterceptor.resetForDetach();
        }

        Assert.assertEquals(0, WatchInterceptor.getActiveWatchCount());
        Assert.assertEquals(0, WatchInterceptor.getPublishedEventCount());
        Assert.assertEquals(0, WatchInterceptor.getDroppedEventCount());
        Assert.assertEquals(0, WatchInterceptor.getEvictedEventCount());
    }

    @Test
    public void resetForDetach_clearsTraceQueuesAndMetrics() {
        String id = "trace-detach-reset";
        BlockingQueue<TraceResult> q = new LinkedBlockingQueue<>(10);
        TraceInterceptor.registerTrace(id, q, 1.0);
        try {
            long start = System.nanoTime();
            TraceInterceptor.onMethodEntry(id, "C", "m", "()V", start);
            TraceInterceptor.onMethodExit(id, "C", "m", "()V", start, 100L, false);
            Assert.assertEquals(1, TraceInterceptor.getActiveTraceCount());
            Assert.assertTrue("expected trace queue to receive events", q.size() > 0);
            Assert.assertTrue(TraceInterceptor.getPublishedEventCount() > 0);
        } finally {
            TraceInterceptor.resetForDetach();
        }

        Assert.assertEquals(0, TraceInterceptor.getActiveTraceCount());
        Assert.assertEquals(0, TraceInterceptor.getPublishedEventCount());
        Assert.assertEquals(0, TraceInterceptor.getDroppedEventCount());
        Assert.assertEquals(0, TraceInterceptor.getEvictedEventCount());
        Assert.assertEquals(0, TraceInterceptor.getSampledOutEventCount());
    }

    @Test
    public void resetForDetach_clearsStackQueuesAndMetrics() {
        String id = "stack-detach-reset";
        BlockingQueue<StackTraceResult> q = new LinkedBlockingQueue<>(10);
        StackInterceptor.register(id, q);
        try {
            StackInterceptor.onMethodEnter(id, "C", "m", "()V", 2);
            Assert.assertTrue("expected stack queue to receive events", q.size() > 0);
            Assert.assertEquals(1, StackInterceptor.getActiveStackCount());
            Assert.assertTrue(StackInterceptor.getPublishedEventCount() > 0);
        } finally {
            StackInterceptor.resetForDetach();
        }

        Assert.assertEquals(0, StackInterceptor.getActiveStackCount());
        Assert.assertEquals(0, StackInterceptor.getPublishedEventCount());
        Assert.assertEquals(0, StackInterceptor.getDroppedEventCount());
        Assert.assertEquals(0, StackInterceptor.getEvictedEventCount());
    }

    @Test
    public void resetForDetach_clearsTtQueuesRecordsAndMetrics() {
        String id = "tt-detach-reset";
        BlockingQueue<TtRecord> q = new LinkedBlockingQueue<>(10);
        TtInterceptor.register(id, q);
        try {
            long start = System.nanoTime();
            TtInterceptor.onMethodExit(id, "C", "m", "()V", new Object[] {"x"}, "y", start, 10L);
            Assert.assertEquals(1, TtInterceptor.getActiveTtCount());
            Assert.assertTrue("expected tt records", !TtInterceptor.list(1).isEmpty());
            Assert.assertTrue("expected tt queue events", q.size() > 0);
            Assert.assertTrue(TtInterceptor.getPublishedCount() > 0);
        } finally {
            TtInterceptor.resetForDetach();
        }

        Assert.assertEquals(0, TtInterceptor.getActiveTtCount());
        List<TtRecord> after = TtInterceptor.list(10);
        Assert.assertTrue("expected tt records cleared on detach reset", after == null || after.isEmpty());
        Assert.assertEquals(0, TtInterceptor.getPublishedCount());
        Assert.assertEquals(0, TtInterceptor.getDroppedCount());
        Assert.assertEquals(0, TtInterceptor.getEvictedCount());
    }

    @Test
    public void resetForDetach_clearsMonitorCollectors() {
        String id = "monitor-detach-reset";
        MonitorInterceptor.registerMonitor(id);
        try {
            MonitorInterceptor.onMethodExit(id, "C", "m", "()V", 100L, false);
            Map<String, MonitorInterceptor.MethodStatsSnapshot> snap = MonitorInterceptor.snapshot(id);
            Assert.assertNotNull(snap);
            Assert.assertFalse("expected monitor stats present", snap.isEmpty());
            Assert.assertEquals(1, MonitorInterceptor.getActiveMonitorCount());
        } finally {
            MonitorInterceptor.resetForDetach();
        }

        Assert.assertEquals(0, MonitorInterceptor.getActiveMonitorCount());
        Assert.assertTrue(MonitorInterceptor.snapshot(id).isEmpty());
    }
}

