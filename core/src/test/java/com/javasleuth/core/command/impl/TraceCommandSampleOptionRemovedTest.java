package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.JobManager;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.test.SleuthTestState;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class TraceCommandSampleOptionRemovedTest {

    @After
    public void cleanup() {
        SleuthTestState.resetAll("TraceCommandSampleOptionRemovedTest");
    }

    @Test
    public void shouldRejectRemovedSampleOptions() throws Exception {
        TraceCommand cmd = new TraceCommand(null, null, ProductionConfig.createDefault(), new JobManager());
        assertRemovedSampleMessage(cmd, new String[] {"trace", "C", "m", "--sample", "0.1"});
        assertRemovedSampleMessage(cmd, new String[] {"trace", "C", "m", "--sample-rate=0.1"});
    }

    private static void assertRemovedSampleMessage(TraceCommand cmd, String[] args) throws Exception {
        try {
            String out = cmd.execute(args);
            Assert.assertNotNull(out);
            String lower = out.toLowerCase();
            Assert.assertTrue("expected error mentioning removed sample option, got: " + out,
                lower.contains("sample") && (lower.contains("remove") || out.contains("已移除")));
        } catch (Exception e) {
            Assert.fail("expected trace to reject removed sample options with a message, not throw: " + e);
        }
    }
}
