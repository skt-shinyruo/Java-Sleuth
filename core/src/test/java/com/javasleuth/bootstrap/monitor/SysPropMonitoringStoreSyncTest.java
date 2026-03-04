package com.javasleuth.bootstrap.monitor;

import com.javasleuth.core.command.impl.SysPropCommand;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.test.SleuthTestState;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class SysPropMonitoringStoreSyncTest {
    private final String oldWatchDropOnFull = System.getProperty("sleuth.monitoring.watch.drop.on.full");
    private final String oldTraceDropOnFull = System.getProperty("sleuth.monitoring.trace.drop.on.full");

    @After
    public void cleanup() {
        restoreSysProp("sleuth.monitoring.watch.drop.on.full", oldWatchDropOnFull);
        restoreSysProp("sleuth.monitoring.trace.drop.on.full", oldTraceDropOnFull);
        SleuthTestState.resetAll("SysPropMonitoringStoreSyncTest");
    }

    @Test
    public void syspropSetMonitoringKeysShouldSyncBootstrapMonitorConfigStore() throws Exception {
        SleuthTestState.resetAll("SysPropMonitoringStoreSyncTest_before");
        BootstrapMonitorConfigStore.clear();

        ProductionConfig config = ProductionConfig.createDefault();
        SysPropCommand cmd = new SysPropCommand(null, config);
        cmd.execute(new String[] {"sysprop", "set", "sleuth.monitoring.watch.drop.on.full", "false"});
        cmd.execute(new String[] {"sysprop", "set", "sleuth.monitoring.trace.drop.on.full", "false"});

        Assert.assertEquals(Boolean.FALSE, BootstrapMonitorConfigStore.getWatchDropOnFull());
        Assert.assertEquals(Boolean.FALSE, BootstrapMonitorConfigStore.getTraceDropOnFull());
    }

    private static void restoreSysProp(String key, String oldValue) {
        if (oldValue == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, oldValue);
    }
}
