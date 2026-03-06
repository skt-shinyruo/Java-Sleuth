package com.javasleuth.core.command;

import com.javasleuth.core.command.impl.TraceCommand;
import com.javasleuth.core.command.impl.TtCommand;
import com.javasleuth.core.command.impl.WatchCommand;
import com.javasleuth.core.command.session.ClientSessionRegistry;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.monitor.TtInterceptor;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;
import com.javasleuth.bootstrap.spy.SleuthSpyAPI;
import org.junit.Assert;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.function.IntSupplier;

public class SessionCleanupOnDisconnectTest {

    public static class DummyTarget {
        public String doWork(String input) {
            return input;
        }
    }

    @Test
    public void testWatchTraceTtAreCleanedOnSessionClose() throws Exception {
        WatchInterceptor.unregisterAllWatches();
        TraceInterceptor.unregisterAllTraces();
        TtInterceptor.unregisterAll();
        SleuthSpyAPI.destroy();

        String clientId = "test-client-" + UUID.randomUUID();
        String clientInfo = "unit-test";
        String sessionId = "test-session-" + UUID.randomUUID();

        ClientSessionRegistry registry = new ClientSessionRegistry();
        registry.close(clientId, "test_setup_cleanup");

        Instrumentation inst = fakeInstrumentationWithLoadedClasses(DummyTarget.class);
        ProductionConfig config = ProductionConfig.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        JobManager jobManager = new JobManager();
        SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
        SleuthSpyAPI.setSpy(dispatcher);
        SleuthSpyAPI.init();

        Thread watchThread = null;
        Thread traceThread = null;
        Thread ttThread = null;
        try {
            ClientSession session = registry.open(clientId, clientInfo, sessionId);
            CommandContext context = new CommandContext(clientId, clientInfo, sessionId, true, session);

            watchThread = startInThreadWithContext(context, () -> {
                try {
                    new WatchCommand(inst, transformer, config, jobManager, dispatcher)
                        .execute(new String[]{"watch", DummyTarget.class.getName(), "doWork", "-t", "30"});
                } catch (Exception ignore) {
                }
            });
            traceThread = startInThreadWithContext(context, () -> {
                try {
                    new TraceCommand(inst, transformer, config, jobManager, dispatcher)
                        .execute(new String[]{"trace", DummyTarget.class.getName(), "doWork", "-t", "30"});
                } catch (Exception ignore) {
                }
            });
            ttThread = startInThreadWithContext(context, () -> {
                try {
                    new TtCommand(inst, transformer, config, jobManager, dispatcher)
                        .execute(new String[]{"tt", DummyTarget.class.getName(), "doWork", "-t", "30"});
                } catch (Exception ignore) {
                }
            });

            awaitAtLeast("watch", dispatcher::getActiveWatchCount, 1, 5000);
            awaitAtLeast("trace", dispatcher::getActiveTraceCount, 1, 5000);
            awaitAtLeast("tt", dispatcher::getActiveTtCount, 1, 5000);

            registry.close(clientId, "disconnect");

            awaitEquals("watch", dispatcher::getActiveWatchCount, 0, 5000);
            awaitEquals("trace", dispatcher::getActiveTraceCount, 0, 5000);
            awaitEquals("tt", dispatcher::getActiveTtCount, 0, 5000);
        } finally {
            registry.close(clientId, "test_teardown_cleanup");
            WatchInterceptor.unregisterAllWatches();
            TraceInterceptor.unregisterAllTraces();
            TtInterceptor.unregisterAll();
            SleuthSpyAPI.destroy();
            stopThread(watchThread);
            stopThread(traceThread);
            stopThread(ttThread);
        }
    }

    private static Instrumentation fakeInstrumentationWithLoadedClasses(Class<?>... loadedClasses) {
        Class<?>[] snapshot = loadedClasses.clone();
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, args) -> {
                if ("getAllLoadedClasses".equals(method.getName())) {
                    return snapshot;
                }
                if ("isModifiableClass".equals(method.getName())) {
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
            });
    }

    private static void awaitAtLeast(String name, IntSupplier supplier, int min, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int last = supplier.getAsInt();
        while (System.currentTimeMillis() < deadline) {
            last = supplier.getAsInt();
            if (last >= min) {
                return;
            }
            Thread.sleep(10);
        }
        Assert.fail(name + " did not reach >= " + min + " within " + timeoutMs + "ms (last=" + last + ")");
    }

    private static void awaitEquals(String name, IntSupplier supplier, int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int last = supplier.getAsInt();
        while (System.currentTimeMillis() < deadline) {
            last = supplier.getAsInt();
            if (last == expected) {
                return;
            }
            Thread.sleep(10);
        }
        Assert.fail(name + " did not reach == " + expected + " within " + timeoutMs + "ms (last=" + last + ")");
    }

    private static Thread startInThreadWithContext(CommandContext context, Runnable task) {
        Thread t = new Thread(() -> {
            CommandContextHolder.set(context);
            try {
                task.run();
            } finally {
                CommandContextHolder.clear();
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void stopThread(Thread t) throws InterruptedException {
        if (t == null) {
            return;
        }
        t.join(200);
        if (t.isAlive()) {
            t.interrupt();
            t.join(500);
        }
    }
}
