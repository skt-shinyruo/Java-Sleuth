package com.javasleuth.core.command;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Test;

public class CommandRegistryShutdownTest {
    private static final class CloseTrackingURLClassLoader extends URLClassLoader {
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private CloseTrackingURLClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public void close() throws java.io.IOException {
            closed.set(true);
            super.close();
        }

        private boolean isClosed() {
            return closed.get();
        }
    }

    @Test
    public void shutdown_closesPluginClassLoader() {
        ProductionConfig config = ProductionConfig.createDefault();
        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CloseTrackingURLClassLoader pluginCl = new CloseTrackingURLClassLoader(new URL[0], null);

            CommandRegistry registry = new CommandRegistry(
                config,
                null,
                auditLogger,
                Collections.<CommandProvider>emptyList(),
                pluginCl
            );

            registry.shutdown();
            Assert.assertTrue(pluginCl.isClosed());

            // Idempotent.
            registry.shutdown();
            Assert.assertTrue(pluginCl.isClosed());
        }
    }
}
