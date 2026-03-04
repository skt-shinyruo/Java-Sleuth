package com.javasleuth.bootstrap.agent;

import java.io.Closeable;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Test;

/**
 * Reflection-based contract test:
 * - Ensures the bootstrap bridge exposes a stable lifecycle SSOT API for the thin agent (JDK-only) to invoke.
 * - Verifies key lifecycle behaviors: exclusive begin, token-guarded side effects, rollback, loader close on detach.
 */
public class AgentLifecycleTest {
    private static final String LIFECYCLE_CLASS = "com.javasleuth.bootstrap.agent.AgentLifecycle";
    private static final String TEST_KEY = "sleuth.test.agentLifecycle.key";

    @Test
    public void lifecycleContract_exclusiveToken_rollbackAndDetach() throws Exception {
        Class<?> lifecycle = Class.forName(LIFECYCLE_CLASS);

        Method tryBeginAttach = lifecycle.getMethod("tryBeginAttach");
        Method applyAgentArgsIfAbsent = lifecycle.getMethod("applyAgentArgsIfAbsent", long.class, String.class);
        Method commitIsolatedClassLoader = lifecycle.getMethod("commitIsolatedClassLoader", long.class, ClassLoader.class);
        Method failBestEffort = lifecycle.getMethod("failBestEffort", long.class, ClassLoader.class);
        Method detachBestEffort = lifecycle.getMethod("detachBestEffort", ClassLoader.class);

        String original = System.getProperty(TEST_KEY);
        System.setProperty(TEST_KEY, "orig");

        long sessionId = 0;
        FakeCloseableLoader loader = null;
        try {
            sessionId = ((Number) tryBeginAttach.invoke(null)).longValue();
            Assert.assertTrue("Expected a non-zero session id", sessionId > 0);

            long second = ((Number) tryBeginAttach.invoke(null)).longValue();
            Assert.assertEquals("Second begin must fail while session is active", 0L, second);

            // Wrong token must not be able to apply side effects.
            Object appliedWrong = applyAgentArgsIfAbsent.invoke(null, 999999L, TEST_KEY + "=v1");
            Assert.assertEquals(Boolean.FALSE, appliedWrong);
            Assert.assertEquals("orig", System.getProperty(TEST_KEY));

            Object appliedOk = applyAgentArgsIfAbsent.invoke(null, sessionId, TEST_KEY + "=v1");
            Assert.assertEquals(Boolean.TRUE, appliedOk);
            Assert.assertEquals("v1", System.getProperty(TEST_KEY));

            loader = new FakeCloseableLoader();
            Object committed = commitIsolatedClassLoader.invoke(null, sessionId, loader);
            Assert.assertEquals(Boolean.TRUE, committed);

            // Wrong loader must not detach (avoid closing the wrong lifecycle boundary).
            detachBestEffort.invoke(null, new FakeCloseableLoader());
            Assert.assertFalse(loader.closed.get());
            Assert.assertEquals("v1", System.getProperty(TEST_KEY));

            // Correct loader detaches: closes loader + rolls back sysprops + clears gate.
            detachBestEffort.invoke(null, loader);
            Assert.assertTrue(loader.closed.get());
            Assert.assertEquals("orig", System.getProperty(TEST_KEY));

            long third = ((Number) tryBeginAttach.invoke(null)).longValue();
            Assert.assertTrue("Expected to re-begin after detach", third > 0);
            failBestEffort.invoke(null, third, null);
        } finally {
            try {
                if (sessionId > 0) {
                    failBestEffort.invoke(null, sessionId, loader);
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
        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public void close() {
            closed.set(true);
        }
    }
}

