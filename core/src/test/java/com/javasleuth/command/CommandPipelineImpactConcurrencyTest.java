package com.javasleuth.command;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.InputValidator;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class CommandPipelineImpactConcurrencyTest {

    @Test
    public void highImpactCommandsAreSingleFlight_byDefaultLimit1() throws Exception {
        String oldAuthz = System.getProperty("sleuth.security.authorization.enabled");
        String oldValidation = System.getProperty("sleuth.security.input.validation");
        String oldImpactConfirm = System.getProperty("sleuth.security.impact.high.confirm.enabled");
        String oldImpactLimit = System.getProperty("sleuth.security.impact.high.concurrent.limit");

        try {
            System.setProperty("sleuth.security.authorization.enabled", "false");
            System.setProperty("sleuth.security.input.validation", "false");
            System.setProperty("sleuth.security.impact.high.confirm.enabled", "false");
            System.setProperty("sleuth.security.impact.high.concurrent.limit", "1");

            ProductionConfig config = ProductionConfig.getInstance();
            CommandPipeline pipeline = new CommandPipeline(new InputValidator(), AuthorizationManager.getInstance(), config);

            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            Command cmd = new Command() {
                @Override
                public String execute(String[] args) throws Exception {
                    started.countDown();
                    assertTrue("test should release command", release.await(5, TimeUnit.SECONDS));
                    return "ok";
                }

                @Override
                public String getDescription() {
                    return "blocking";
                }
            };

            CommandRegistry.Entry entry = new CommandRegistry.Entry(
                cmd,
                CommandMeta.viewer(false, false).withImpact(CommandMeta.ImpactLevel.HIGH),
                "test"
            );

            CommandContext ctx = new CommandContext("c1", "i1", "s1", false, false);

            AtomicReference<CommandPipeline.Result> r1 = new AtomicReference<>();
            Thread t = new Thread(() -> r1.set(pipeline.execute(entry, new String[]{"dump"}, ctx)), "t1");
            t.setDaemon(true);
            t.start();

            assertTrue("command should start", started.await(2, TimeUnit.SECONDS));

            CommandPipeline.Result r2 = pipeline.execute(entry, new String[]{"dump"}, ctx);
            assertFalse(r2.isSuccess());
            assertNotNull(r2.getError());
            assertTrue(r2.getError().toLowerCase().contains("high impact"));

            release.countDown();
            t.join(5000);

            assertNotNull(r1.get());
            assertTrue(r1.get().isSuccess());
            assertEquals("ok", r1.get().getOutput());
        } finally {
            setOrClearProperty("sleuth.security.authorization.enabled", oldAuthz);
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
            setOrClearProperty("sleuth.security.impact.high.confirm.enabled", oldImpactConfirm);
            setOrClearProperty("sleuth.security.impact.high.concurrent.limit", oldImpactLimit);
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

