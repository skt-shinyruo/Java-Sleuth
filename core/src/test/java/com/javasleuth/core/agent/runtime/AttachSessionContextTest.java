package com.javasleuth.core.agent.runtime;

import com.javasleuth.bootstrap.monitor.BootstrapMonitorConfigStore;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.monitor.VmToolInterceptor;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;
import com.javasleuth.core.command.CommandProcessor;
import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.session.ClientSessionRegistry;
import com.javasleuth.core.enhancement.ClassEnhancer;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.bootstrap.data.TraceResult;
import com.javasleuth.bootstrap.data.WatchResult;
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.test.SleuthTestState;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.ClassVisitor;
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

    @Test
    public void close_recordsPartialCleanupFailuresAndContinuesRemainingSteps() {
        String oldConsole = System.getProperty("sleuth.logging.console.enabled");
        PrintStream oldErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        AtomicInteger retransformAttempts = new AtomicInteger(0);
        AtomicInteger removeTransformerAttempts = new AtomicInteger(0);
        AttachSessionContext session = AttachSessionContext.create(
            fakeInstrumentation(
                new Class<?>[]{CleanupTarget.class},
                retransformAttempts,
                removeTransformerAttempts,
                true
            ),
            () -> {}
        );

        try {
            System.setProperty("sleuth.logging.console.enabled", "true");
            System.setErr(new PrintStream(err));

            session.getTransformer().addEnhancer(CleanupTarget.class, passthroughEnhancer());
            session.getEnhancementSessionRegistry().register(
                EnhancementSessionDescriptor.builder("cleanup-failing-watch", EnhancementSessionKind.WATCH).build(),
                reason -> {
                    throw new IllegalStateException("session close boom");
                }
            );

            session.close();

            CleanupResult result = AgentGlobalState.getLastRuntimeCleanupResult();
            Assert.assertNotNull(result);
            Assert.assertTrue(result.isDegraded());
            Assert.assertEquals(0, session.getEnhancementSessionRegistry().size());
            Assert.assertEquals(0, session.getTransformer().getActiveEnhancersCount());
            Assert.assertEquals(1, retransformAttempts.get());
            Assert.assertEquals(1, removeTransformerAttempts.get());
            Assert.assertTrue(result.formatFailures().contains("session close boom"));
            Assert.assertTrue(result.formatFailures().contains("rollback boom"));
            Assert.assertTrue(result.formatFailures().contains("remove transformer boom"));

            String log = new String(err.toByteArray(), StandardCharsets.UTF_8);
            Assert.assertTrue(log.contains("attach cleanup completed with failures"));
            Assert.assertTrue(log.contains("remove transformer boom"));
        } finally {
            System.setErr(oldErr);
            setOrClearProperty("sleuth.logging.console.enabled", oldConsole);
        }
    }

    @Test
    public void close_recordsSpyCleanupFailureAndContinuesRemainingSteps() throws Exception {
        AtomicInteger removeTransformerAttempts = new AtomicInteger(0);
        SleuthAgentServices services = SleuthAgentServices.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(services.getConfig());
        AttachSessionContext session = newContextForCleanupTest(
            fakeInstrumentation(new Class<?>[0], null, removeTransformerAttempts, false),
            transformer,
            services,
            new FailingSpyDispatcher()
        );

        session.close();

        CleanupResult result = AgentGlobalState.getLastRuntimeCleanupResult();
        Assert.assertNotNull(result);
        Assert.assertTrue(result.isDegraded());
        Assert.assertTrue(result.formatFailures().contains("clear-spy-dispatcher"));
        Assert.assertTrue(result.formatFailures().contains("spy clear boom"));
        Assert.assertEquals(1, removeTransformerAttempts.get());
    }

    private static Instrumentation fakeInstrumentation() {
        return fakeInstrumentation(new Class<?>[0], null, null, false);
    }

    private static Instrumentation fakeInstrumentation(
        Class<?>[] loadedClasses,
        AtomicInteger retransformAttempts,
        AtomicInteger removeTransformerAttempts,
        boolean failCleanupOperations
    ) {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getAllLoadedClasses".equals(name)) {
                    return loadedClasses != null ? loadedClasses : new Class<?>[0];
                }
                if ("isModifiableClass".equals(name)) {
                    return true;
                }
                if ("retransformClasses".equals(name)) {
                    if (retransformAttempts != null) {
                        retransformAttempts.incrementAndGet();
                    }
                    if (failCleanupOperations) {
                        throw new RuntimeException("rollback boom");
                    }
                    return null;
                }
                if ("removeTransformer".equals(name)) {
                    if (removeTransformerAttempts != null) {
                        removeTransformerAttempts.incrementAndGet();
                    }
                    if (failCleanupOperations) {
                        throw new RuntimeException("remove transformer boom");
                    }
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

    private static ClassEnhancer passthroughEnhancer() {
        return new ClassEnhancer() {
            @Override
            public ClassVisitor createClassVisitor(ClassVisitor delegate, String className) {
                return delegate;
            }

            @Override
            public String getDescription() {
                return "cleanup-test";
            }
        };
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class CleanupTarget {
    }

    private static AttachSessionContext newContextForCleanupTest(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        SleuthAgentServices services,
        SleuthSpyDispatcher spyDispatcher
    ) throws Exception {
        Constructor<AttachSessionContext> constructor = AttachSessionContext.class.getDeclaredConstructor(
            Instrumentation.class,
            SleuthClassFileTransformer.class,
            SleuthAgentServices.class,
            ClientSessionRegistry.class,
            MetricsCollector.class,
            JobManager.class,
            VmToolSessionRegistry.class,
            EnhancementSessionRegistry.class,
            CommandProcessor.class,
            SleuthSpyDispatcher.class,
            Thread.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
            instrumentation,
            transformer,
            services,
            null,
            null,
            null,
            null,
            null,
            null,
            spyDispatcher,
            null
        );
    }

    private static final class FailingSpyDispatcher extends SleuthSpyDispatcher {
        @Override
        public void clear() {
            throw new RuntimeException("spy clear boom");
        }
    }

    private static Object readBootstrapConfigField(String fieldName) throws Exception {
        java.lang.reflect.Field field = BootstrapMonitorConfigStore.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }
}
