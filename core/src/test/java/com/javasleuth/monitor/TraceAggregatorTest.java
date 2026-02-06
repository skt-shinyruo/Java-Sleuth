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
}

