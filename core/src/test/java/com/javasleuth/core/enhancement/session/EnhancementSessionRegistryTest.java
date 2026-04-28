package com.javasleuth.core.enhancement.session;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class EnhancementSessionRegistryTest {

    @Test
    public void registerListCountAndCloseById() {
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        AtomicInteger closed = new AtomicInteger(0);

        EnhancementSessionHandle handle = registry.register(
            EnhancementSessionDescriptor.builder("watch-1", EnhancementSessionKind.WATCH)
                .withClientId("client-a")
                .withClientSessionId("session-a")
                .withCommandName("watch")
                .withClassPattern("com.example.Demo")
                .withMethodPattern("run")
                .withTargetClassNames(Collections.singletonList("com.example.Demo"))
                .withLoaderIds(Collections.singletonList(123))
                .withDetails("count=1")
                .build(),
            reason -> closed.incrementAndGet()
        );

        Assert.assertEquals("watch-1", handle.getSessionId());
        Assert.assertEquals(1, registry.size());
        Assert.assertEquals(1, registry.list().size());
        Assert.assertEquals("client-a", registry.list().get(0).getClientId());

        Map<EnhancementSessionKind, Integer> counts = registry.countByKind();
        Assert.assertEquals(Integer.valueOf(1), counts.get(EnhancementSessionKind.WATCH));
        Assert.assertEquals(Integer.valueOf(0), counts.get(EnhancementSessionKind.TRACE));

        Assert.assertTrue(registry.close("watch-1", "test"));
        Assert.assertFalse(registry.close("watch-1", "second"));
        Assert.assertTrue(handle.isClosed());
        Assert.assertEquals(1, closed.get());
        Assert.assertEquals(0, registry.size());
    }

    @Test
    public void closeByClientOnlyClosesMatchingSessions() {
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        AtomicInteger clientAClosed = new AtomicInteger(0);
        AtomicInteger clientBClosed = new AtomicInteger(0);

        registry.register(
            EnhancementSessionDescriptor.builder("trace-1", EnhancementSessionKind.TRACE)
                .withClientId("client-a")
                .withCommandName("trace")
                .build(),
            reason -> clientAClosed.incrementAndGet()
        );
        registry.register(
            EnhancementSessionDescriptor.builder("tt-1", EnhancementSessionKind.TT)
                .withClientId("client-b")
                .withCommandName("tt")
                .build(),
            reason -> clientBClosed.incrementAndGet()
        );

        Assert.assertEquals(1, registry.closeByClient("client-a", "disconnect"));
        Assert.assertEquals(1, clientAClosed.get());
        Assert.assertEquals(0, clientBClosed.get());
        Assert.assertEquals(1, registry.size());
        Assert.assertEquals("tt-1", registry.list().get(0).getSessionId());
    }

    @Test
    public void closeAllIsIdempotentAndRecordsFailures() {
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        AtomicInteger closed = new AtomicInteger(0);

        registry.register(
            EnhancementSessionDescriptor.builder("stack-1", EnhancementSessionKind.STACK)
                .withTargetClassNames(Arrays.asList("a.A", "b.B"))
                .build(),
            reason -> closed.incrementAndGet()
        );
        registry.register(
            EnhancementSessionDescriptor.builder("monitor-1", EnhancementSessionKind.MONITOR)
                .build(),
            reason -> {
                throw new IllegalStateException("boom");
            }
        );

        EnhancementSessionCloseSummary summary = registry.closeAll("reset");

        Assert.assertEquals(2, summary.getTotal());
        Assert.assertEquals(1, summary.getClosed());
        Assert.assertEquals(1, summary.getFailed());
        Assert.assertEquals(1, closed.get());
        Assert.assertEquals(0, registry.size());
        Assert.assertTrue(summary.getFailureMessages().get("monitor-1").contains("boom"));

        EnhancementSessionCloseSummary second = registry.closeAll("reset-again");
        Assert.assertEquals(0, second.getTotal());
        Assert.assertEquals(0, second.getClosed());
        Assert.assertEquals(0, second.getFailed());
    }

    @Test
    public void duplicateRegistrationClosesOldHandle() {
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        AtomicInteger oldClosed = new AtomicInteger(0);
        AtomicInteger newClosed = new AtomicInteger(0);

        registry.register(
            EnhancementSessionDescriptor.builder("same-id", EnhancementSessionKind.WATCH).build(),
            reason -> oldClosed.incrementAndGet()
        );
        registry.register(
            EnhancementSessionDescriptor.builder("same-id", EnhancementSessionKind.TRACE).build(),
            reason -> newClosed.incrementAndGet()
        );

        Assert.assertEquals(1, oldClosed.get());
        Assert.assertEquals(0, newClosed.get());
        Assert.assertEquals(Integer.valueOf(0), registry.countByKind().get(EnhancementSessionKind.WATCH));
        Assert.assertEquals(Integer.valueOf(1), registry.countByKind().get(EnhancementSessionKind.TRACE));
    }
}
