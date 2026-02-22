package com.javasleuth.core.command.impl;

import java.lang.reflect.Field;
import org.junit.Assert;
import org.junit.Test;

public class ProfilerCommandCloseTest {

    @Test(timeout = 5000)
    public void close_should_shutdown_scheduler_and_clear_reference() throws Exception {
        ProfilerCommand cmd = new ProfilerCommand(null);

        String started = cmd.execute(new String[]{"profiler", "start", "cpu", "0", "10"});
        Assert.assertNotNull(started);
        Assert.assertTrue("expected profiler start output", started.toLowerCase().contains("started"));

        cmd.close();

        Field f = ProfilerCommand.class.getDeclaredField("scheduler");
        f.setAccessible(true);
        Object ex = f.get(cmd);
        Assert.assertNull("expected scheduler cleared after close()", ex);
    }
}
