package com.javasleuth.core.command;

import com.javasleuth.core.command.impl.EnhanceCommand;
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class EnhanceCommandTest {
    @Test
    public void sessionsListsActiveEnhancementSessionsAndFiltersByKind() throws Exception {
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        registry.register(
            EnhancementSessionDescriptor.builder("watch-1", EnhancementSessionKind.WATCH)
                .withClientId("client-a")
                .withCommandName("watch")
                .withClassPattern("com.example.Service")
                .withMethodPattern("run")
                .withTargetClassNames(Arrays.asList("com.example.Service"))
                .withDetails("count=5")
                .build(),
            reason -> {
            }
        );
        registry.register(
            EnhancementSessionDescriptor.builder("trace-1", EnhancementSessionKind.TRACE)
                .withClientId("client-b")
                .withCommandName("trace")
                .build(),
            reason -> {
            }
        );

        String output = new EnhanceCommand(registry).execute(new String[] {"enhance", "sessions", "--kind", "watch"});

        Assert.assertTrue(output.contains("watch-1"));
        Assert.assertTrue(output.contains("WATCH"));
        Assert.assertTrue(output.contains("client-a"));
        Assert.assertTrue(output.contains("com.example.Service"));
        Assert.assertFalse(output.contains("trace-1"));
    }

    @Test
    public void stopCanCloseByIdClientOrKind() throws Exception {
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        AtomicInteger closed = new AtomicInteger(0);
        registry.register(
            EnhancementSessionDescriptor.builder("watch-1", EnhancementSessionKind.WATCH)
                .withClientId("client-a")
                .build(),
            reason -> closed.incrementAndGet()
        );
        registry.register(
            EnhancementSessionDescriptor.builder("trace-1", EnhancementSessionKind.TRACE)
                .withClientId("client-a")
                .build(),
            reason -> closed.incrementAndGet()
        );
        registry.register(
            EnhancementSessionDescriptor.builder("monitor-1", EnhancementSessionKind.MONITOR)
                .withClientId("client-b")
                .build(),
            reason -> closed.incrementAndGet()
        );

        EnhanceCommand command = new EnhanceCommand(registry);
        String byId = command.execute(new String[] {"enhance", "stop", "watch-1"});
        Assert.assertTrue(byId.contains("closed=1"));
        Assert.assertEquals(2, registry.size());

        String byClient = command.execute(new String[] {"enhance", "stop", "--client", "client-a"});
        Assert.assertTrue(byClient.contains("closed=1"));
        Assert.assertEquals(1, registry.size());

        String byKind = command.execute(new String[] {"enhance", "stop", "--kind", "monitor"});
        Assert.assertTrue(byKind.contains("closed=1"));
        Assert.assertEquals(0, registry.size());
        Assert.assertEquals(3, closed.get());
    }

    @Test
    public void stopReportsMissingAndFailedSessions() throws Exception {
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        registry.register(
            EnhancementSessionDescriptor.builder("watch-1", EnhancementSessionKind.WATCH).build(),
            reason -> {
                throw new IllegalStateException("close failed");
            }
        );

        EnhanceCommand command = new EnhanceCommand(registry);

        Assert.assertTrue(command.execute(new String[] {"enhance", "stop", "missing"}).contains("No matching enhancement sessions"));
        String failed = command.execute(new String[] {"enhance", "stop", "watch-1"});
        Assert.assertTrue(failed.contains("failed=1"));
        Assert.assertTrue(failed.contains("watch-1"));
        Assert.assertTrue(failed.contains("close failed"));
    }
}
