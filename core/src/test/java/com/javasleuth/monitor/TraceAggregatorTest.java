package com.javasleuth.monitor;

import com.javasleuth.data.TraceResult;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class TraceAggregatorTest {
    @Test
    public void aggregatesInvocationTree() {
        TraceAggregator agg = new TraceAggregator(new TraceAggregator.Options().withMaxDepth(10).withMaxNodes(100));

        TraceResult entry = new TraceResult();
        entry.setTraceId("t1");
        entry.setClassName("com.example.Foo");
        entry.setMethodName("work");
        entry.setMethodDescriptor("()V");
        entry.setStartTime(1L);
        entry.setEventType(TraceResult.EventType.METHOD_ENTRY);
        entry.setThreadId(1L);
        entry.setThreadName("main");

        TraceResult sub = new TraceResult();
        sub.setTraceId("t1");
        sub.setClassName("java.lang.String");
        sub.setMethodName("valueOf");
        sub.setMethodDescriptor("(I)Ljava/lang/String;");
        sub.setStartTime(2L);
        sub.setEventType(TraceResult.EventType.SUB_METHOD_CALL);
        sub.setThreadId(1L);
        sub.setThreadName("main");

        TraceResult exit = new TraceResult();
        exit.setTraceId("t1");
        exit.setClassName("com.example.Foo");
        exit.setMethodName("work");
        exit.setMethodDescriptor("()V");
        exit.setStartTime(1L);
        exit.setDuration(5_000_000L);
        exit.setEventType(TraceResult.EventType.METHOD_EXIT);
        exit.setThreadId(1L);
        exit.setThreadName("main");

        agg.accept(entry);
        agg.accept(sub);
        agg.accept(exit);

        List<TraceAggregator.Invocation> inv = agg.drainCompleted();
        assertEquals(1, inv.size());
        TraceAggregator.Invocation i = inv.get(0);
        assertEquals("t1", i.getTraceId());
        assertEquals(1L, i.getThreadId());
        assertEquals("main", i.getThreadName());
        assertNotNull(i.getRoot());
        assertEquals("com.example.Foo", i.getRoot().getClassName());
        assertEquals("work", i.getRoot().getMethodName());
        assertEquals(5_000_000L, i.getRoot().getDuration());
        assertEquals(1, i.getRoot().getChildren().size());
        assertTrue(i.getRoot().getChildren().get(0) instanceof TraceAggregator.SubCall);
    }

    @Test
    public void doesNotBreakStackWhenDroppingEntriesAtMaxNodes() {
        TraceAggregator agg = new TraceAggregator(new TraceAggregator.Options().withMaxDepth(10).withMaxNodes(100));

        TraceResult rootEntry = new TraceResult();
        rootEntry.setTraceId("t1");
        rootEntry.setClassName("com.example.Root");
        rootEntry.setMethodName("root");
        rootEntry.setMethodDescriptor("()V");
        rootEntry.setStartTime(1L);
        rootEntry.setEventType(TraceResult.EventType.METHOD_ENTRY);
        rootEntry.setThreadId(1L);
        rootEntry.setThreadName("main");
        agg.accept(rootEntry);

        // Fill up maxNodes with SUB_METHOD_CALLs, then a nested METHOD_ENTRY will be dropped.
        for (int i = 0; i < 99; i++) {
            TraceResult sub = new TraceResult();
            sub.setTraceId("t1");
            sub.setClassName("com.example.Sub");
            sub.setMethodName("sub" + i);
            sub.setMethodDescriptor("()V");
            sub.setStartTime(2L + i);
            sub.setEventType(TraceResult.EventType.SUB_METHOD_CALL);
            sub.setThreadId(1L);
            sub.setThreadName("main");
            agg.accept(sub);
        }

        TraceResult childEntry = new TraceResult();
        childEntry.setTraceId("t1");
        childEntry.setClassName("com.example.Child");
        childEntry.setMethodName("child");
        childEntry.setMethodDescriptor("()V");
        childEntry.setStartTime(200L);
        childEntry.setEventType(TraceResult.EventType.METHOD_ENTRY);
        childEntry.setThreadId(1L);
        childEntry.setThreadName("main");
        agg.accept(childEntry);

        TraceResult childExit = new TraceResult();
        childExit.setTraceId("t1");
        childExit.setClassName("com.example.Child");
        childExit.setMethodName("child");
        childExit.setMethodDescriptor("()V");
        childExit.setStartTime(200L);
        childExit.setDuration(10L);
        childExit.setEventType(TraceResult.EventType.METHOD_EXIT);
        childExit.setThreadId(1L);
        childExit.setThreadName("main");
        agg.accept(childExit);

        // The dropped child's exit must not pop/complete the root invocation.
        assertTrue(agg.drainCompleted().isEmpty());

        TraceResult rootExit = new TraceResult();
        rootExit.setTraceId("t1");
        rootExit.setClassName("com.example.Root");
        rootExit.setMethodName("root");
        rootExit.setMethodDescriptor("()V");
        rootExit.setStartTime(1L);
        rootExit.setDuration(123L);
        rootExit.setEventType(TraceResult.EventType.METHOD_EXIT);
        rootExit.setThreadId(1L);
        rootExit.setThreadName("main");
        agg.accept(rootExit);

        List<TraceAggregator.Invocation> inv = agg.drainCompleted();
        assertEquals(1, inv.size());
        assertEquals(123L, inv.get(0).getRoot().getDuration());
        assertEquals(99, inv.get(0).getRoot().getChildren().size());
    }
}
