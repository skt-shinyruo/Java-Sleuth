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
        ProductionConfig config = ProductionConfig.getInstance();
        BootstrapMonitorConfigStore.clear();

        ConfigCommand cmd = new ConfigCommand(config);

        cmd.execute(new String[] {"config", "set", "monitoring.trace.sample.rate", "0.42"});
        Double fromStore = BootstrapMonitorConfigStore.getTraceSampleRate();
        Assert.assertNotNull(fromStore);
        Assert.assertEquals(0.42d, fromStore.doubleValue(), 0.000001d);

        cmd.execute(new String[] {"config", "remove", "monitoring.trace.sample.rate"});
        SleuthConfig typedAfterRemove = SleuthConfigParser.parse(config.snapshot());
        Double fromStoreAfterRemove = BootstrapMonitorConfigStore.getTraceSampleRate();
        Assert.assertNotNull(fromStoreAfterRemove);
        Assert.assertEquals(typedAfterRemove.monitoring().getTraceSampleRate(), fromStoreAfterRemove.doubleValue(), 0.000001d);

        cmd.execute(new String[] {"config", "clear"});
        SleuthConfig typedAfterClear = SleuthConfigParser.parse(config.snapshot());
        Double fromStoreAfterClear = BootstrapMonitorConfigStore.getTraceSampleRate();
        Assert.assertNotNull(fromStoreAfterClear);
        Assert.assertEquals(typedAfterClear.monitoring().getTraceSampleRate(), fromStoreAfterClear.doubleValue(), 0.000001d);
    }
}

