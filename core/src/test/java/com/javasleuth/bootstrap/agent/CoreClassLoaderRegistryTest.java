package com.javasleuth.bootstrap.agent;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class CoreClassLoaderRegistryTest {

    @After
    public void tearDown() {
        // Avoid noisy warnings in unit tests; runtime default remains enabled.
        System.setProperty("sleuth.bootstrap.registry.warn.nonbootstrap", "false");

        ClassLoader current = CoreClassLoaderRegistry.getRegistered();
        if (current != null) {
            CoreClassLoaderRegistry.onCoreShutdown(current);
        }
        assertFalse(CoreClassLoaderRegistry.isRegistered());
    }

    @Test
    public void registerAndReleaseIsIdempotent() {
        System.setProperty("sleuth.bootstrap.registry.warn.nonbootstrap", "false");

        FakeCloseableLoader loader1 = new FakeCloseableLoader();
        assertTrue(CoreClassLoaderRegistry.tryRegister(loader1));
        assertTrue(CoreClassLoaderRegistry.isRegistered());
        assertSame(loader1, CoreClassLoaderRegistry.getRegistered());

        FakeCloseableLoader loader2 = new FakeCloseableLoader();
        assertFalse(CoreClassLoaderRegistry.tryRegister(loader2));
        assertTrue(CoreClassLoaderRegistry.isRegistered());
        assertFalse(loader1.closed.get());
        assertFalse(loader2.closed.get());

        // Wrong loader should not release the registered one.
        CoreClassLoaderRegistry.onCoreShutdown(loader2);
        assertTrue(CoreClassLoaderRegistry.isRegistered());
        assertFalse(loader1.closed.get());

        // Correct loader release should close and clear.
        CoreClassLoaderRegistry.onCoreShutdown(loader1);
        assertFalse(CoreClassLoaderRegistry.isRegistered());
        assertTrue(loader1.closed.get());

        // Idempotent: releasing again should not throw.
        CoreClassLoaderRegistry.onCoreShutdown(loader1);

        // Can register again after release.
        assertTrue(CoreClassLoaderRegistry.tryRegister(loader2));
        assertTrue(CoreClassLoaderRegistry.isRegistered());
        assertSame(loader2, CoreClassLoaderRegistry.getRegistered());

        CoreClassLoaderRegistry.releaseOnFailure(loader2);
        assertFalse(CoreClassLoaderRegistry.isRegistered());
        assertTrue(loader2.closed.get());
    }

    private static final class FakeCloseableLoader extends ClassLoader implements Closeable {
        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public void close() {
            closed.set(true);
        }
    }
}

