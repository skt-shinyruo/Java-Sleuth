package com.javasleuth.core.spy;

import com.javasleuth.bootstrap.data.StackTraceResult;
import com.javasleuth.bootstrap.data.TraceResult;
import com.javasleuth.bootstrap.data.TtRecord;
import com.javasleuth.bootstrap.data.WatchResult;
import com.javasleuth.bootstrap.monitor.MonitorInterceptor;
import com.javasleuth.bootstrap.monitor.StackInterceptor;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.monitor.TtInterceptor;
import com.javasleuth.bootstrap.monitor.VmToolInterceptor;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;
import com.javasleuth.bootstrap.spy.SleuthSpyAPI;
import com.javasleuth.core.command.impl.tt.TtRecordStore;
import com.javasleuth.core.spy.listener.MonitorAdviceListener;
import com.javasleuth.core.spy.listener.TraceAdviceListener;
import com.javasleuth.core.spy.listener.TtAdviceListener;
import com.javasleuth.core.spy.listener.WatchAdviceListener;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.Assert;
import org.junit.Test;

public class SleuthSpyDispatcherTest {

    @Test
    public void spyApiDefaultFallbackIsNopAndDoesNotPublishToLegacyRegistry() throws Exception {
        URL bootstrapLocation = SleuthSpyAPI.class.getProtectionDomain().getCodeSource().getLocation();
        URLClassLoader loader = new URLClassLoader(new URL[]{bootstrapLocation}, null);
        try {
            Class<?> api = Class.forName("com.javasleuth.bootstrap.spy.SleuthSpyAPI", true, loader);
            Class<?> watch = Class.forName("com.javasleuth.bootstrap.monitor.WatchInterceptor", true, loader);
            BlockingQueue<Object> legacyQueue = new LinkedBlockingQueue<>();

            Method registerWatch = watch.getMethod("registerWatch", String.class, BlockingQueue.class);
            registerWatch.invoke(null, "legacy-id", legacyQueue);

            Method atEnter = api.getMethod(
                "atEnter",
                String.class,
                Class.class,
                String.class,
                Object.class,
                Object[].class,
                long.class
            );
            atEnter.invoke(null, "legacy-id", String.class, "trim|()Ljava/lang/String;", "value", new Object[0], 100L);

            Assert.assertTrue("uninstalled spy API must be a NOP fallback", legacyQueue.isEmpty());
        } finally {
            loader.close();
        }
    }

    @Test
    public void spyApiDispatchesWatchTraceMonitorAndTtThroughInstalledDispatcher() {
        resetLegacyState();
        SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
        BlockingQueue<WatchResult> watchQueue = new LinkedBlockingQueue<>();
        BlockingQueue<TraceResult> traceQueue = new LinkedBlockingQueue<>();
        BlockingQueue<TtRecord> ttQueue = new LinkedBlockingQueue<>();
        TtRecordStore ttStore = new TtRecordStore(16);
        MonitorAdviceListener monitorListener = new MonitorAdviceListener();

        try {
            SleuthSpyAPI.setSpy(dispatcher);
            SleuthSpyAPI.init();
            dispatcher.register("watch-id", SleuthSpyDispatcher.ListenerKind.WATCH, new WatchAdviceListener("watch-id", watchQueue, false));
            dispatcher.register("trace-id", SleuthSpyDispatcher.ListenerKind.TRACE, new TraceAdviceListener("trace-id", traceQueue, false));
            dispatcher.register("monitor-id", SleuthSpyDispatcher.ListenerKind.MONITOR, monitorListener);
            dispatcher.register("tt-id", SleuthSpyDispatcher.ListenerKind.TT, new TtAdviceListener("tt-id", ttQueue, ttStore, false));

            SleuthSpyAPI.atEnter("watch-id", String.class, "trim|()Ljava/lang/String;", "value", new Object[0], 100L);
            SleuthSpyAPI.atExit("watch-id", String.class, "trim|()Ljava/lang/String;", "value", null, "x", true, 100L, 50L);
            SleuthSpyAPI.atExceptionExit("watch-id", String.class, "trim|()Ljava/lang/String;", "value", null, new RuntimeException("boom"), 100L, 60L);

            SleuthSpyAPI.atEnter("trace-id", String.class, "trim|()Ljava/lang/String;", "value", null, 200L);
            SleuthSpyAPI.atBeforeInvoke("trace-id", String.class, "java/lang/String|substring|(I)Ljava/lang/String;|12", "value", 220L);
            SleuthSpyAPI.atAfterInvoke("trace-id", String.class, "java/lang/String|substring|(I)Ljava/lang/String;|12", "value", 240L);
            SleuthSpyAPI.atExit("trace-id", String.class, "trim|()Ljava/lang/String;", "value", null, null, true, 200L, 80L);

            SleuthSpyAPI.atExit("monitor-id", String.class, "trim|()Ljava/lang/String;", "value", null, null, true, 300L, 30L);
            SleuthSpyAPI.atExceptionExit("monitor-id", String.class, "trim|()Ljava/lang/String;", "value", null, new RuntimeException("boom"), 300L, 40L);

            SleuthSpyAPI.atExit("tt-id", String.class, "trim|()Ljava/lang/String;", "value", new Object[]{"in"}, "out", true, 400L, 70L);
            SleuthSpyAPI.atExceptionExit("tt-id", String.class, "trim|()Ljava/lang/String;", "value", new Object[]{"in"}, new RuntimeException("boom"), 400L, 90L);

            Assert.assertEquals(3, watchQueue.size());
            Assert.assertEquals(WatchResult.EventType.METHOD_ENTRY, watchQueue.poll().getEventType());
            Assert.assertEquals(WatchResult.EventType.METHOD_EXIT, watchQueue.poll().getEventType());
            Assert.assertEquals(WatchResult.EventType.METHOD_EXCEPTION, watchQueue.poll().getEventType());

            Assert.assertEquals(3, traceQueue.size());
            Assert.assertEquals(TraceResult.EventType.METHOD_ENTRY, traceQueue.poll().getEventType());
            Assert.assertEquals(TraceResult.EventType.SUB_METHOD_CALL, traceQueue.poll().getEventType());
            Assert.assertEquals(TraceResult.EventType.METHOD_EXIT, traceQueue.poll().getEventType());

            Map<String, MonitorAdviceListener.MethodStatsSnapshot> monitor = monitorListener.snapshot();
            Assert.assertEquals(1, monitor.size());
            MonitorAdviceListener.MethodStatsSnapshot stats = monitor.values().iterator().next();
            Assert.assertEquals(2L, stats.getCount());
            Assert.assertEquals(1L, stats.getExceptionCount());

            Assert.assertEquals(2, ttQueue.size());
            Assert.assertEquals(2, ttStore.list(10).size());
            Assert.assertEquals(TtRecord.EventType.METHOD_EXIT, ttQueue.poll().getEventType());
            Assert.assertEquals(TtRecord.EventType.METHOD_EXCEPTION, ttQueue.poll().getEventType());
        } finally {
            dispatcher.clear();
            SleuthSpyAPI.destroy();
            resetLegacyState();
        }
    }

    @Test
    public void spyApiCallsAfterDestroyAreSafeAndDoNotDispatch() {
        resetLegacyState();
        SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
        BlockingQueue<WatchResult> watchQueue = new LinkedBlockingQueue<>();
        BlockingQueue<TraceResult> traceQueue = new LinkedBlockingQueue<>();
        BlockingQueue<TtRecord> ttQueue = new LinkedBlockingQueue<>();
        TtRecordStore ttStore = new TtRecordStore(16);
        MonitorAdviceListener monitorListener = new MonitorAdviceListener();

        try {
            SleuthSpyAPI.setSpy(dispatcher);
            SleuthSpyAPI.init();
            dispatcher.register("watch-id", SleuthSpyDispatcher.ListenerKind.WATCH, new WatchAdviceListener("watch-id", watchQueue, false));
            dispatcher.register("trace-id", SleuthSpyDispatcher.ListenerKind.TRACE, new TraceAdviceListener("trace-id", traceQueue, false));
            dispatcher.register("monitor-id", SleuthSpyDispatcher.ListenerKind.MONITOR, monitorListener);
            dispatcher.register("tt-id", SleuthSpyDispatcher.ListenerKind.TT, new TtAdviceListener("tt-id", ttQueue, ttStore, false));

            SleuthSpyAPI.atEnter("watch-id", String.class, "trim|()Ljava/lang/String;", "value", new Object[0], 100L);
            SleuthSpyAPI.atEnter("trace-id", String.class, "trim|()Ljava/lang/String;", "value", null, 200L);
            SleuthSpyAPI.atExit("monitor-id", String.class, "trim|()Ljava/lang/String;", "value", null, null, true, 300L, 30L);
            SleuthSpyAPI.atExit("tt-id", String.class, "trim|()Ljava/lang/String;", "value", new Object[]{"in"}, "out", true, 400L, 70L);

            Assert.assertEquals(1, watchQueue.size());
            Assert.assertEquals(1, traceQueue.size());
            Assert.assertEquals(1L, monitorListener.snapshot().values().iterator().next().getCount());
            Assert.assertEquals(1, ttQueue.size());

            dispatcher.clear();
            SleuthSpyAPI.destroy();

            SleuthSpyAPI.atEnter("watch-id", String.class, "trim|()Ljava/lang/String;", "value", new Object[0], 500L);
            SleuthSpyAPI.atExit("watch-id", String.class, "trim|()Ljava/lang/String;", "value", null, "x", true, 500L, 50L);
            SleuthSpyAPI.atExceptionExit("watch-id", String.class, "trim|()Ljava/lang/String;", "value", null, new RuntimeException("boom"), 500L, 60L);
            SleuthSpyAPI.atBeforeInvoke("trace-id", String.class, "java/lang/String|substring|(I)Ljava/lang/String;|12", "value", 520L);
            SleuthSpyAPI.atAfterInvoke("trace-id", String.class, "java/lang/String|substring|(I)Ljava/lang/String;|12", "value", 540L);
            SleuthSpyAPI.atInvokeException("trace-id", String.class, "java/lang/String|substring|(I)Ljava/lang/String;|12", "value", new RuntimeException("boom"), 560L);
            SleuthSpyAPI.onConstructed("vmtool-id", new Object());

            Assert.assertEquals(1, watchQueue.size());
            Assert.assertEquals(1, traceQueue.size());
            Assert.assertEquals(1L, monitorListener.snapshot().values().iterator().next().getCount());
            Assert.assertEquals(1, ttQueue.size());
            Assert.assertEquals(1, ttStore.list(10).size());
        } finally {
            dispatcher.clear();
            SleuthSpyAPI.destroy();
            resetLegacyState();
        }
    }

    @Test
    public void dispatcherDoesNotFallbackToLegacyRegistries_whenNoListenerIsRegistered() {
        String listenerId = "legacy-compat-test";
        BlockingQueue<WatchResult> watchQueue = new LinkedBlockingQueue<>();
        BlockingQueue<TraceResult> traceQueue = new LinkedBlockingQueue<>();
        BlockingQueue<StackTraceResult> stackQueue = new LinkedBlockingQueue<>();
        BlockingQueue<TtRecord> ttQueue = new LinkedBlockingQueue<>();

        WatchInterceptor.unregisterAllWatches();
        TraceInterceptor.unregisterAllTraces();
        StackInterceptor.unregisterAll();
        TtInterceptor.unregisterAll();
        TtInterceptor.clear();
        MonitorInterceptor.unregisterAllMonitors();
        VmToolInterceptor.clearAll();

        WatchInterceptor.registerWatch(listenerId, watchQueue);
        TraceInterceptor.registerTrace(listenerId, traceQueue);
        StackInterceptor.register(listenerId, stackQueue);
        TtInterceptor.register(listenerId, ttQueue);
        MonitorInterceptor.registerMonitor(listenerId);
        MonitorInterceptor.clear(listenerId);
        VmToolInterceptor.registerTrack(listenerId, "java.lang.Object", 10);

        SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
        Object instance = new Object();
        try {
            dispatcher.atEnter(listenerId, String.class, "trim|()Ljava/lang/String;", "value", new Object[0], 100L);
            dispatcher.atExit(listenerId, String.class, "trim|()Ljava/lang/String;", "value", new Object[0], "x", true, 100L, 50L);
            dispatcher.atExceptionExit(listenerId, String.class, "trim|()Ljava/lang/String;", "value", new Object[0], new RuntimeException("boom"), 100L, 50L);
            dispatcher.atBeforeInvoke(listenerId, String.class, "java/lang/String|substring|(I)Ljava/lang/String;|12", "value", 120L);
            dispatcher.atAfterInvoke(listenerId, String.class, "java/lang/String|substring|(I)Ljava/lang/String;|12", "value", 125L);
            dispatcher.atInvokeException(listenerId, String.class, "java/lang/String|substring|(I)Ljava/lang/String;|12", "value", new RuntimeException("boom"), 130L);
            dispatcher.onConstructed(listenerId, instance);

            Assert.assertTrue("watch queue should stay empty", watchQueue.isEmpty());
            Assert.assertTrue("trace queue should stay empty", traceQueue.isEmpty());
            Assert.assertTrue("stack queue should stay empty", stackQueue.isEmpty());
            Assert.assertTrue("tt queue should stay empty", ttQueue.isEmpty());
            Assert.assertTrue("tt records should stay empty", TtInterceptor.list(10).isEmpty());
            Assert.assertEquals("monitor snapshot should stay empty", 0, MonitorInterceptor.snapshot(listenerId).size());
            Assert.assertEquals("vmtool captured count should stay zero", 0L, VmToolInterceptor.getTrackStats(listenerId).getCapturedTotal());
            Assert.assertEquals("vmtool live count should stay zero", 0, VmToolInterceptor.getTrackStats(listenerId).getAlive());
        } finally {
            WatchInterceptor.unregisterAllWatches();
            TraceInterceptor.unregisterAllTraces();
            StackInterceptor.unregisterAll();
            TtInterceptor.unregisterAll();
            TtInterceptor.clear();
            MonitorInterceptor.unregisterAllMonitors();
            VmToolInterceptor.clearAll();
        }
    }

    @Test
    public void dispatcherRoutesOnlyToRegisteredListener() {
        SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
        CapturingListener listener = new CapturingListener();

        dispatcher.register("listener", SleuthSpyDispatcher.ListenerKind.WATCH, listener);

        dispatcher.atEnter("listener", String.class, "trim|()Ljava/lang/String;", "value", new Object[0], 100L);
        dispatcher.atExit("listener", String.class, "trim|()Ljava/lang/String;", "value", new Object[0], "x", true, 100L, 50L);
        dispatcher.atExceptionExit("listener", String.class, "trim|()Ljava/lang/String;", "value", new Object[0], new RuntimeException("boom"), 100L, 50L);
        dispatcher.atBeforeInvoke("listener", String.class, "java/lang/String|substring|(I)Ljava/lang/String;|12", "value", 120L);
        dispatcher.atAfterInvoke("listener", String.class, "java/lang/String|substring|(I)Ljava/lang/String;|12", "value", 125L);
        dispatcher.atInvokeException("listener", String.class, "java/lang/String|substring|(I)Ljava/lang/String;|12", "value", new RuntimeException("boom"), 130L);
        dispatcher.onConstructed("listener", new Object());

        Assert.assertEquals(1, listener.enterCount);
        Assert.assertEquals(1, listener.exitCount);
        Assert.assertEquals(1, listener.exceptionExitCount);
        Assert.assertEquals(1, listener.beforeInvokeCount);
        Assert.assertEquals(1, listener.afterInvokeCount);
        Assert.assertEquals(1, listener.invokeExceptionCount);
        Assert.assertEquals(1, listener.constructedCount);
        Assert.assertEquals(1, dispatcher.getActiveWatchCount());
        Assert.assertEquals(1, dispatcher.getActiveListenerCount());
    }

    private static void resetLegacyState() {
        WatchInterceptor.unregisterAllWatches();
        TraceInterceptor.unregisterAllTraces();
        StackInterceptor.unregisterAll();
        TtInterceptor.unregisterAll();
        TtInterceptor.clear();
        MonitorInterceptor.unregisterAllMonitors();
        VmToolInterceptor.clearAll();
    }

    private static final class CapturingListener implements SleuthAdviceListener {
        int enterCount;
        int exitCount;
        int exceptionExitCount;
        int beforeInvokeCount;
        int afterInvokeCount;
        int invokeExceptionCount;
        int constructedCount;

        @Override
        public void onEnter(Class<?> clazz, String methodInfo, Object target, Object[] args, long startNanos) {
            enterCount++;
        }

        @Override
        public void onExit(
            Class<?> clazz,
            String methodInfo,
            Object target,
            Object[] args,
            Object returnObject,
            boolean returnCaptured,
            long startNanos,
            long durationNanos
        ) {
            exitCount++;
        }

        @Override
        public void onExceptionExit(
            Class<?> clazz,
            String methodInfo,
            Object target,
            Object[] args,
            Throwable throwable,
            long startNanos,
            long durationNanos
        ) {
            exceptionExitCount++;
        }

        @Override
        public void onBeforeInvoke(Class<?> clazz, String invokeInfo, Object target, long whenNanos) {
            beforeInvokeCount++;
        }

        @Override
        public void onAfterInvoke(Class<?> clazz, String invokeInfo, Object target, long whenNanos) {
            afterInvokeCount++;
        }

        @Override
        public void onInvokeException(Class<?> clazz, String invokeInfo, Object target, Throwable throwable, long whenNanos) {
            invokeExceptionCount++;
        }

        @Override
        public void onConstructed(Object instance) {
            constructedCount++;
        }
    }
}
