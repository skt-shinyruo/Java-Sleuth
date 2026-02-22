package com.javasleuth.bootstrap.monitor;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class VmToolInterceptorTest {

    @Test
    public void trackIsBoundedAndListsInstances() {
        String id = "t-" + System.nanoTime();
        assertTrue(VmToolInterceptor.registerTrack(id, "com.example.Foo", 3));

        Object a = new Object();
        Object b = new Object();
        Object c = new Object();
        Object d = new Object();
        Object e = new Object();

        VmToolInterceptor.onConstructed(id, a);
        VmToolInterceptor.onConstructed(id, b);
        VmToolInterceptor.onConstructed(id, c);
        VmToolInterceptor.onConstructed(id, d);
        VmToolInterceptor.onConstructed(id, e);

        VmToolInterceptor.TrackStats stats = VmToolInterceptor.getTrackStats(id);
        assertNotNull(stats);
        assertEquals(3, stats.getMaxEntries());
        assertTrue(stats.getCapturedTotal() >= 5);
        assertTrue(stats.getCached() <= 3);

        List<VmToolInterceptor.TrackedInstanceInfo> list = VmToolInterceptor.listInstances(id, 50, false);
        assertFalse(list.isEmpty());
        assertTrue(list.size() <= 3);

        VmToolInterceptor.unregisterTrack(id);
        assertTrue(VmToolInterceptor.listInstances(id, 10, false).isEmpty());
    }
}
