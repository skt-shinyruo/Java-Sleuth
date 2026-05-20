package com.javasleuth.core.enhancement.session;

import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.core.enhancement.ClassEnhancer;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.spy.SleuthAdviceListener;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.foundation.config.ProductionConfig;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.objectweb.asm.ClassVisitor;

public class EnhancementSessionManagerTest {
    @Test
    public void openRegistersListenerEnhancerRegistryAndClientCleanup() throws Exception {
        RecordingInstrumentation instrumentation = new RecordingInstrumentation();
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(ProductionConfig.createDefault());
        EnhancementSessionManager manager = new EnhancementSessionManager(instrumentation.proxy(), transformer, dispatcher, registry);
        ClientSession clientSession = new ClientSession("session-a", "client-a", "unit-test");
        CommandContextHolder.set(new CommandContext("client-a", "unit-test", "session-a", true, clientSession));
        try {
            EnhancementSessionManager.ManagedSession session = manager.open(
                EnhancementSessionManager.Request.builder("watch-1", EnhancementSessionKind.WATCH)
                    .withListenerKind(SleuthSpyDispatcher.ListenerKind.WATCH)
                    .withListener(new NoopListener())
                    .withClassPattern(ManagerTarget.class.getName())
                    .withMethodPattern("work")
                    .withTarget(ManagerTarget.class, new NoopEnhancer("watch"))
                    .withDetails("count=1")
                    .build()
            );

            Assert.assertEquals("watch-1", session.getSessionId());
            Assert.assertEquals(1, dispatcher.getActiveWatchCount());
            Assert.assertEquals(1, transformer.getActiveEnhancersCount());
            Assert.assertEquals(1, instrumentation.retransformCalls.size());
            Assert.assertEquals(1, registry.size());
            EnhancementSessionSnapshot snapshot = registry.list().get(0);
            Assert.assertEquals("client-a", snapshot.getClientId());
            Assert.assertEquals("session-a", snapshot.getClientSessionId());
            Assert.assertEquals(Collections.singletonList(ManagerTarget.class.getName()), snapshot.getTargetClassNames());

            clientSession.close("disconnect");

            Assert.assertEquals(0, dispatcher.getActiveWatchCount());
            Assert.assertEquals(0, transformer.getActiveEnhancersCount());
            Assert.assertEquals(0, registry.size());
            Assert.assertEquals(2, instrumentation.retransformCalls.size());
        } finally {
            CommandContextHolder.clear();
            registry.closeAll("test_cleanup");
            dispatcher.clear();
        }
    }

    @Test
    public void closeSessionIsIdempotentAndRemovesClientCleanup() throws Exception {
        RecordingInstrumentation instrumentation = new RecordingInstrumentation();
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(ProductionConfig.createDefault());
        EnhancementSessionManager manager = new EnhancementSessionManager(instrumentation.proxy(), transformer, dispatcher, registry);
        ClientSession clientSession = new ClientSession("session-b", "client-b", "unit-test");
        CommandContextHolder.set(new CommandContext("client-b", "unit-test", "session-b", true, clientSession));
        try {
            EnhancementSessionManager.ManagedSession session = manager.open(
                EnhancementSessionManager.Request.builder("trace-1", EnhancementSessionKind.TRACE)
                    .withListenerKind(SleuthSpyDispatcher.ListenerKind.TRACE)
                    .withListener(new NoopListener())
                    .withClassPattern(ManagerTarget.class.getName())
                    .withMethodPattern("work")
                    .withTarget(ManagerTarget.class, new NoopEnhancer("trace"))
                    .build()
            );

            Assert.assertTrue(session.close("completed"));
            Assert.assertFalse(session.close("again"));
            clientSession.close("disconnect-after-complete");

            Assert.assertEquals(0, dispatcher.getActiveTraceCount());
            Assert.assertEquals(0, transformer.getActiveEnhancersCount());
            Assert.assertEquals(0, registry.size());
            Assert.assertEquals(2, instrumentation.retransformCalls.size());
        } finally {
            CommandContextHolder.clear();
            registry.closeAll("test_cleanup");
            dispatcher.clear();
        }
    }

    @Test
    public void retransformFailureRollsBackEnhancerAndListenerWithoutRegistrySession() throws Exception {
        RecordingInstrumentation instrumentation = new RecordingInstrumentation();
        instrumentation.failOnRetransform = true;
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(ProductionConfig.createDefault());
        EnhancementSessionManager manager = new EnhancementSessionManager(instrumentation.proxy(), transformer, dispatcher, registry);

        try {
            manager.open(
                EnhancementSessionManager.Request.builder("stack-1", EnhancementSessionKind.STACK)
                    .withListenerKind(SleuthSpyDispatcher.ListenerKind.STACK)
                    .withListener(new NoopListener())
                    .withClassPattern(ManagerTarget.class.getName())
                    .withMethodPattern("work")
                    .withTarget(ManagerTarget.class, new NoopEnhancer("stack"))
                    .build()
            );
            Assert.fail("Expected retransform failure");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("boom"));
        }

        Assert.assertEquals(0, dispatcher.getActiveStackCount());
        Assert.assertEquals(0, transformer.getActiveEnhancersCount());
        Assert.assertEquals(0, registry.size());
        Assert.assertEquals(2, instrumentation.retransformCalls.size());
    }

    public static class ManagerTarget {
        public void work() {
        }
    }

    private static final class NoopListener implements SleuthAdviceListener {
    }

    private static final class NoopEnhancer implements ClassEnhancer {
        private final String description;

        private NoopEnhancer(String description) {
            this.description = description;
        }

        @Override
        public ClassVisitor createClassVisitor(ClassVisitor delegate, String className) {
            return delegate;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    private static final class RecordingInstrumentation {
        private final List<Class<?>> retransformCalls = new ArrayList<>();
        private boolean failOnRetransform;

        private Instrumentation proxy() {
            return (Instrumentation) Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("retransformClasses".equals(name)) {
                        if (args != null && args.length == 1 && args[0] instanceof Class[]) {
                            Class<?>[] classes = (Class<?>[]) args[0];
                            if (classes != null) {
                                Collections.addAll(retransformCalls, classes);
                            }
                        }
                        if (failOnRetransform) {
                            throw new IllegalStateException("boom");
                        }
                        return null;
                    }
                    if ("isModifiableClass".equals(name)) {
                        return true;
                    }
                    if ("getAllLoadedClasses".equals(name)) {
                        return new Class<?>[]{ManagerTarget.class};
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
    }
}
