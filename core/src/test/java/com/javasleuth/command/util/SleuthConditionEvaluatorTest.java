package com.javasleuth.core.command.util;

import com.javasleuth.bootstrap.data.WatchResult;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SleuthConditionEvaluatorTest {
    @Test
    public void costSupportsTimeSuffix() {
        WatchResult r = new WatchResult();
        r.setClassName("com.example.Foo");
        r.setMethodName("doWork");
        r.setDuration(5_000_000L); // 5ms
        r.setEventType(WatchResult.EventType.METHOD_EXIT);

        List<SleuthConditionEvaluator.Condition> c1 =
            SleuthConditionEvaluator.parseConditions(Arrays.asList("cost:gt:1ms"));
        assertTrue(SleuthConditionEvaluator.matchesWatch(r, c1));

        List<SleuthConditionEvaluator.Condition> c2 =
            SleuthConditionEvaluator.parseConditions(Arrays.asList("cost:lt:10ms"));
        assertTrue(SleuthConditionEvaluator.matchesWatch(r, c2));

        List<SleuthConditionEvaluator.Condition> c3 =
            SleuthConditionEvaluator.parseConditions(Arrays.asList("method:eq:doWork"));
        assertTrue(SleuthConditionEvaluator.matchesWatch(r, c3));

        List<SleuthConditionEvaluator.Condition> c4 =
            SleuthConditionEvaluator.parseConditions(Arrays.asList("class:contains:example"));
        assertTrue(SleuthConditionEvaluator.matchesWatch(r, c4));
    }
}
