package com.javasleuth.core.agent.core;

import com.javasleuth.bootstrap.agent.AgentLifecycle;
import java.io.Closeable;
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

    private static final class FakeCloseableLoader extends ClassLoader implements Closeable {
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

        @Override
        public void close() {
            closed.set(true);
        }
    }
}

