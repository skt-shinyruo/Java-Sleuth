package com.javasleuth.bootstrap.monitor;

import com.javasleuth.core.command.impl.ConfigCommand;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.test.SleuthTestState;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class BootstrapMonitoringConfigSyncTest {

    @After
    public void cleanup() {
        SleuthTestState.resetAll("BootstrapMonitoringConfigSyncTest");
    }

    @Test
    public void configSetRemoveClearShouldSyncBootstrapMonitorConfigStore() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        BootstrapMonitorConfigStore.clear();

        ConfigCommand cmd = new ConfigCommand(config);

        cmd.execute(new String[] {"config", "set", "monitoring.watch.drop.on.full", "false"});
        Assert.assertEquals(Boolean.FALSE, BootstrapMonitorConfigStore.getWatchDropOnFull());

        cmd.execute(new String[] {"config", "set", "monitoring.trace.drop.on.full", "false"});
        Assert.assertEquals(Boolean.FALSE, BootstrapMonitorConfigStore.getTraceDropOnFull());

        cmd.execute(new String[] {"config", "remove", "monitoring.watch.drop.on.full"});
        SleuthConfig typedAfterRemove = SleuthConfigParser.parse(config.snapshot());
        Assert.assertEquals(Boolean.valueOf(typedAfterRemove.monitoring().isWatchDropOnFull()),
            BootstrapMonitorConfigStore.getWatchDropOnFull());

        cmd.execute(new String[] {"config", "clear"});
        SleuthConfig typedAfterClear = SleuthConfigParser.parse(config.snapshot());
        Assert.assertEquals(Boolean.valueOf(typedAfterClear.monitoring().isWatchDropOnFull()),
            BootstrapMonitorConfigStore.getWatchDropOnFull());
        Assert.assertEquals(Boolean.valueOf(typedAfterClear.monitoring().isTraceDropOnFull()),
            BootstrapMonitorConfigStore.getTraceDropOnFull());
    }
}
