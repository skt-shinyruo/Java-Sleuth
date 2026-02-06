package com.javasleuth.monitor;

import com.javasleuth.data.TtRecord;
import com.javasleuth.data.WatchResult;
import com.javasleuth.util.SleuthSnapshotValue;
import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class WatchAndTtSnapshotTest {

    @Test
    public void watchInterceptorSnapshotsParameters() throws Exception {
        BlockingQueue<WatchResult> q = new LinkedBlockingQueue<>(8);
        String id = "watch-test";
        WatchInterceptor.registerWatch(id, q);
        try {
            Object original = new Object();
            WatchInterceptor.onMethodEntry(id, "C", "m", "()V", new Object[]{original}, 1L);
            WatchResult r = q.poll(1, TimeUnit.SECONDS);
            assertNotNull(r);
            assertNotNull(r.getParameters());
            assertEquals(1, r.getParameters().length);
            assertTrue(r.getParameters()[0] instanceof SleuthSnapshotValue);
        } finally {
            WatchInterceptor.unregisterWatch(id);
        }
    }

    @Test
    public void ttInterceptorSnapshotsParametersAndException() throws Exception {
        BlockingQueue<TtRecord> q = new LinkedBlockingQueue<>(8);
        String id = "tt-test";
        TtInterceptor.register(id, q);
        try {
            Object original = new Object();
            Throwable ex = new IllegalStateException("bad");
            TtInterceptor.onMethodException(id, "C", "m", "()V", new Object[]{original}, ex, 1L, 2L);
            TtRecord r = q.poll(1, TimeUnit.SECONDS);
            assertNotNull(r);
            assertNotNull(r.getParameters());
            assertEquals(1, r.getParameters().length);
            assertTrue(r.getParameters()[0] instanceof SleuthSnapshotValue);
            assertNotNull(r.getException());
            assertTrue(r.getException() instanceof SleuthSnapshotValue);
        } finally {
            TtInterceptor.unregister(id);
        }
    }
}

