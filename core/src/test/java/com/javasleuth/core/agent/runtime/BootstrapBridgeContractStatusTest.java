package com.javasleuth.core.agent.runtime;

import org.junit.Assert;
import org.junit.Test;

public class BootstrapBridgeContractStatusTest {

    @Test
    public void relaxedModeReportsClasspathVisibleContractVersion() {
        Assert.assertFalse(BootstrapBridge.isStrictMode());

        String status = BootstrapBridge.describeContractStatus();

        Assert.assertTrue(status, status.startsWith("OK"));
        Assert.assertTrue(status, status.contains("contract=1"));
        Assert.assertTrue(status, status.contains("classpath-visible"));
    }

    @Test
    public void relaxedModeReadsBootstrapContractVersionFromClasspath() {
        Assert.assertFalse(BootstrapBridge.isStrictMode());

        Assert.assertEquals(1, BootstrapBridge.bootstrapContractVersion());
    }
}
