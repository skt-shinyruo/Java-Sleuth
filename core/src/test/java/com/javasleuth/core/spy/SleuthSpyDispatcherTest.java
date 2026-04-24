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
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.Assert;
import org.junit.Test;

public class SleuthSpyDispatcherTest {

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
