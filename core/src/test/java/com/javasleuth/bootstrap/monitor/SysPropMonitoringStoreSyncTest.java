package com.javasleuth.bootstrap.monitor;

import com.javasleuth.core.command.impl.SysPropCommand;
import com.javasleuth.test.SleuthTestState;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class SysPropMonitoringStoreSyncTest {
    private final String oldWatchDropOnFull = System.getProperty("sleuth.monitoring.watch.drop.on.full");
    private final String oldTraceDropOnFull = System.getProperty("sleuth.monitoring.trace.drop.on.full");
    private final String oldTraceSampleRate = System.getProperty("sleuth.monitoring.trace.sample.rate");
    private final String oldMonitorSampleRate = System.getProperty("sleuth.monitoring.monitor.sample.rate");

    @After
    public void cleanup() {
        restoreSysProp("sleuth.monitoring.watch.drop.on.full", oldWatchDropOnFull);
        restoreSysProp("sleuth.monitoring.trace.drop.on.full", oldTraceDropOnFull);
        restoreSysProp("sleuth.monitoring.trace.sample.rate", oldTraceSampleRate);
        restoreSysProp("sleuth.monitoring.monitor.sample.rate", oldMonitorSampleRate);
        SleuthTestState.resetAll("SysPropMonitoringStoreSyncTest");
    }

    @Test
    public void syspropSetMonitoringKeysShouldSyncBootstrapMonitorConfigStore() throws Exception {
        SleuthTestState.resetAll("SysPropMonitoringStoreSyncTest_before");
        BootstrapMonitorConfigStore.clear();

        SysPropCommand cmd = new SysPropCommand(null);
        cmd.execute(new String[] {"sysprop", "set", "sleuth.monitoring.watch.drop.on.full", "false"});
        cmd.execute(new String[] {"sysprop", "set", "sleuth.monitoring.trace.drop.on.full", "false"});
        cmd.execute(new String[] {"sysprop", "set", "sleuth.monitoring.trace.sample.rate", "0.33"});
        cmd.execute(new String[] {"sysprop", "set", "sleuth.monitoring.monitor.sample.rate", "0.77"});

        Assert.assertEquals(Boolean.FALSE, BootstrapMonitorConfigStore.getWatchDropOnFull());
        Assert.assertEquals(Boolean.FALSE, BootstrapMonitorConfigStore.getTraceDropOnFull());
        Assert.assertEquals(0.33d, BootstrapMonitorConfigStore.getTraceSampleRate().doubleValue(), 0.000001d);
        Assert.assertEquals(0.77d, BootstrapMonitorConfigStore.getMonitorSampleRate().doubleValue(), 0.000001d);
    }

    private static void restoreSysProp(String key, String oldValue) {
        if (oldValue == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, oldValue);
    }
}

