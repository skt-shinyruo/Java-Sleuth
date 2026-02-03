package com.javasleuth.command;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.InputValidator;
import com.javasleuth.util.PerformanceOptimizer;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class CommandPipelineCacheIsolationTest {

    @Test
    public void cacheKeyIncludesClientId_toAvoidCrossClientLeakage() {
        String oldAuthz = System.getProperty("sleuth.security.authorization.enabled");
        String oldValidation = System.getProperty("sleuth.security.input.validation");

        try {
            System.setProperty("sleuth.security.authorization.enabled", "false");
            System.setProperty("sleuth.security.input.validation", "false");

            PerformanceOptimizer.clearCache();

            ProductionConfig config = ProductionConfig.getInstance();
            CommandPipeline pipeline = new CommandPipeline(new InputValidator(), AuthorizationManager.getInstance(), config);

            AtomicInteger seq = new AtomicInteger(0);
            Command cmd = new Command() {
                @Override
                public String execute(String[] args) {
                    return "v=" + seq.incrementAndGet();
                }

                @Override
                public String getDescription() {
                    return "counter";
                }
            };

            CommandRegistry.Entry entry = new CommandRegistry.Entry(cmd, CommandMeta.viewer(true, false), "test");

            CommandPipeline.Result r1 = pipeline.execute(entry, new String[]{"foo"}, new CommandContext("c1", "i1", "s1", false, false));
            CommandPipeline.Result r2 = pipeline.execute(entry, new String[]{"foo"}, new CommandContext("c1", "i1", "s1", false, false));
            CommandPipeline.Result r3 = pipeline.execute(entry, new String[]{"foo"}, new CommandContext("c2", "i2", "s2", false, false));

            assertTrue(r1.isSuccess());
            assertTrue(r2.isSuccess());
            assertTrue(r3.isSuccess());

            assertEquals(r1.getOutput(), r2.getOutput());
            assertNotEquals(r1.getOutput(), r3.getOutput());
            assertEquals("v=1", r1.getOutput());
            assertEquals("v=2", r3.getOutput());
        } finally {
            setOrClearProperty("sleuth.security.authorization.enabled", oldAuthz);
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
        }
    }

    @Test
    public void dashboardRealtimeBypassesCache_evenWhenMetaCacheable() {
        String oldAuthz = System.getProperty("sleuth.security.authorization.enabled");
        String oldValidation = System.getProperty("sleuth.security.input.validation");

        try {
            System.setProperty("sleuth.security.authorization.enabled", "false");
            System.setProperty("sleuth.security.input.validation", "false");

            PerformanceOptimizer.clearCache();

            ProductionConfig config = ProductionConfig.getInstance();
            CommandPipeline pipeline = new CommandPipeline(new InputValidator(), AuthorizationManager.getInstance(), config);

            AtomicInteger seq = new AtomicInteger(0);
            Command cmd = new Command() {
                @Override
                public String execute(String[] args) {
                    return "v=" + seq.incrementAndGet();
                }

                @Override
                public String getDescription() {
                    return "counter";
                }
            };

            CommandRegistry.Entry entry = new CommandRegistry.Entry(cmd, CommandMeta.viewer(true, false), "test");

            CommandContext ctx = new CommandContext("c1", "i1", "s1", false, false);

            CommandPipeline.Result r1 = pipeline.execute(entry, new String[]{"dashboard", "realtime"}, ctx);
            CommandPipeline.Result r2 = pipeline.execute(entry, new String[]{"dashboard", "realtime"}, ctx);

            assertTrue(r1.isSuccess());
            assertTrue(r2.isSuccess());
            assertNotEquals("realtime should bypass cache", r1.getOutput(), r2.getOutput());
            assertEquals("v=1", r1.getOutput());
            assertEquals("v=2", r2.getOutput());
        } finally {
            setOrClearProperty("sleuth.security.authorization.enabled", oldAuthz);
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
        }
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
