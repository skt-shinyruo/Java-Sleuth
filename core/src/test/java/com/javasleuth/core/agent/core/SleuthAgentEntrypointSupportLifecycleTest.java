package com.javasleuth.core.agent.core;

import com.javasleuth.bootstrap.agent.AgentLifecycle;
import java.io.Closeable;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Test;

public class SleuthAgentEntrypointSupportLifecycleTest {
    private static final String TEST_KEY = "sleuth.test.entrypointSupport.lifecycle.key";

    @Test
    public void shutdown_detachesBootstrapLifecycle_and_rollsBackSysprops() {
        String original = System.getProperty(TEST_KEY);
        System.setProperty(TEST_KEY, "orig");

        long sessionId = 0;
        FakeCloseableLoader loader = new FakeCloseableLoader();
        try {
            sessionId = AgentLifecycle.tryBeginAttach();
            Assert.assertTrue(sessionId > 0);
            Assert.assertTrue(AgentLifecycle.applyAgentArgsIfAbsent(sessionId, TEST_KEY + "=v1"));
            Assert.assertEquals("v1", System.getProperty(TEST_KEY));
            Assert.assertTrue(AgentLifecycle.commitIsolatedClassLoader(sessionId, loader));

            AtomicBoolean attachedGate = new AtomicBoolean(true);
            AtomicReference<com.javasleuth.core.agent.runtime.SleuthAgentRuntime> runtimeRef = new AtomicReference<>(null);

            SleuthAgentEntrypointSupport.shutdown(attachedGate, runtimeRef, loader, null, null);

            Assert.assertFalse(attachedGate.get());
            Assert.assertTrue("Expected lifecycle loader to be closed on shutdown", loader.closed.get());
            Assert.assertEquals("orig", System.getProperty(TEST_KEY));

            long after = AgentLifecycle.tryBeginAttach();
            Assert.assertTrue("Expected lifecycle gate cleared after shutdown", after > 0);
            AgentLifecycle.failBestEffort(after, null);
        } finally {
            try {
                if (sessionId > 0) {
                    AgentLifecycle.failBestEffort(sessionId, loader);
                }
            } catch (Throwable ignore) {
                // best-effort cleanup
            }

            if (original == null) {
                System.clearProperty(TEST_KEY);
            } else {
                System.setProperty(TEST_KEY, original);
            }
        }
    }

    @Test
    public void agentmain_propagatesStartupFailureAndClearsAttachedGate() {
        String oldBind = System.getProperty("sleuth.server.bind.address");
        AtomicBoolean attachedGate = new AtomicBoolean(false);
        AtomicReference<com.javasleuth.core.agent.runtime.SleuthAgentRuntime> runtimeRef = new AtomicReference<>(null);

        try {
            System.setProperty("sleuth.server.bind.address", "0.0.0.0");

            try {
                SleuthAgentEntrypointSupport.agentmain(
                    null,
                    fakeInstrumentation(),
                    attachedGate,
                    runtimeRef,
                    null,
                    null,
                    null,
                    "start failed: "
                );
                Assert.fail("Expected startup failure to propagate");
            } catch (IllegalStateException expected) {
                Assert.assertTrue(String.valueOf(expected.getMessage()).contains("command processor"));
            }

            Assert.assertFalse(attachedGate.get());
            Assert.assertNull(runtimeRef.get());
        } finally {
            if (oldBind == null) {
                System.clearProperty("sleuth.server.bind.address");
            } else {
                System.setProperty("sleuth.server.bind.address", oldBind);
            }
        }
    }

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
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

    private static final class FakeCloseableLoader extends ClassLoader implements Closeable {
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
