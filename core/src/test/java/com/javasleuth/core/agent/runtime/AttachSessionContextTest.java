package com.javasleuth.core.agent.runtime;

import com.javasleuth.bootstrap.monitor.BootstrapMonitorConfigStore;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.monitor.VmToolInterceptor;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;
import com.javasleuth.bootstrap.data.TraceResult;
import com.javasleuth.bootstrap.data.WatchResult;
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.test.SleuthTestState;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class AttachSessionContextTest {

    @After
    public void tearDown() {
        SleuthTestState.resetAll("after_test");
    }

    @Test
    public void create_providesAttachScopedRuntimeResources_andCloseIsIdempotent() {
        AttachSessionContext session = AttachSessionContext.create(fakeInstrumentation(), () -> {});
        try {
            Assert.assertNotNull(session.getInstrumentation());
            Assert.assertNotNull(session.getTransformer());
            Assert.assertNotNull(session.getServices());
            Assert.assertNotNull(session.getConfig());
            Assert.assertNotNull(session.getCommandProcessor());
            Assert.assertNotNull(session.getClientSessionRegistry());
            Assert.assertNotNull(session.getMetricsCollector());
            Assert.assertNotNull(session.getJobManager());
            Assert.assertNotNull(session.getVmToolSessionRegistry());
            Assert.assertNotNull(session.getEnhancementSessionRegistry());
            Assert.assertNotNull(session.getSpyDispatcher());
        } finally {
            session.close();
            session.close();
        }
    }

    @Test
    public void close_clearsBootstrapAttachScopeStores() throws Exception {
        AttachSessionContext session = AttachSessionContext.create(fakeInstrumentation(), () -> {});
        AtomicInteger closed = new AtomicInteger(0);
        try {
            session.getEnhancementSessionRegistry().register(
                EnhancementSessionDescriptor.builder("attach-close-watch", EnhancementSessionKind.WATCH).build(),
                reason -> closed.incrementAndGet()
            );
            BootstrapMonitorConfigStore.setWatchDropOnFull(false);
            BootstrapMonitorConfigStore.setTraceDropOnFull(false);
            WatchInterceptor.registerWatch("legacy-watch", new LinkedBlockingQueue<WatchResult>(2));
            TraceInterceptor.registerTrace("legacy-trace", new LinkedBlockingQueue<TraceResult>(2));
            VmToolInterceptor.registerTrack("legacy-track", "java.lang.Object", 8);
        } finally {
            session.close();
        }

        Assert.assertEquals(0, WatchInterceptor.getActiveWatchCount());
        Assert.assertEquals(0, TraceInterceptor.getActiveTraceCount());
        Assert.assertTrue(VmToolInterceptor.listTrackStats().isEmpty());
        Assert.assertEquals(1, closed.get());
        Assert.assertEquals(0, session.getEnhancementSessionRegistry().size());
        Assert.assertNull(readBootstrapConfigField("watchDropOnFull"));
        Assert.assertNull(readBootstrapConfigField("traceDropOnFull"));
    }

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getAllLoadedClasses".equals(name)) {
                    return new Class<?>[0];
                }
                if ("isModifiableClass".equals(name)) {
                    return true;
                }
                if ("removeTransformer".equals(name)) {
                    return true;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == Void.TYPE) {
                    return null;
                }
                if (returnType == Boolean.TYPE) {
                    return false;
                }
                if (returnType == Integer.TYPE) {
                    return 0;
                }
                if (returnType == Long.TYPE) {
                    return 0L;
                }
                if (returnType.isArray()) {
                    return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                }
                return null;
            }
        );
    }

    private static Object readBootstrapConfigField(String fieldName) throws Exception {
        java.lang.reflect.Field field = BootstrapMonitorConfigStore.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }
}
