package com.javasleuth.core.agent.core;

import com.javasleuth.agent.BootstrapAttachGate;
import org.junit.Assert;
import org.junit.Test;

public class BootstrapAttachGateResetTest {

    @Test
    public void resetBestEffort_resetsGateByReflection() {
        BootstrapAttachGate.resetForReattach();
        Assert.assertTrue(BootstrapAttachGate.tryEnter());
        Assert.assertTrue(BootstrapAttachGate.isAttached());

        BootstrapAttachGateReset.resetBestEffort(null);

        Assert.assertFalse(BootstrapAttachGate.isAttached());

        Assert.assertTrue(BootstrapAttachGate.tryEnter());
        BootstrapAttachGate.resetForReattach();
    }

    @Test
    public void shutdown_alwaysResetsBootstrapGate() {
        BootstrapAttachGate.resetForReattach();
        BootstrapAttachGate.tryEnter();
        Assert.assertTrue(BootstrapAttachGate.isAttached());

        SleuthAgentCore.shutdown();

        Assert.assertFalse(BootstrapAttachGate.isAttached());
    }
}

