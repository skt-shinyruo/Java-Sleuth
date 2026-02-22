package com.javasleuth.core.vmtool;

import com.javasleuth.core.agent.core.SleuthAgentCore;
import com.javasleuth.bootstrap.monitor.VmToolInterceptor;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class VmToolShutdownCleanupTest {

    @Test
    public void shutdown_should_clear_vmtool_interceptor_state_best_effort() {
        VmToolInterceptor.registerTrack("t-test", "java.lang.String", 10);

        Map<String, VmToolInterceptor.TrackStats> before = VmToolInterceptor.listTrackStats();
        Assert.assertNotNull(before);
        Assert.assertFalse("expected vmtool track stats present before shutdown", before.isEmpty());

        SleuthAgentCore.shutdown();

        Map<String, VmToolInterceptor.TrackStats> after = VmToolInterceptor.listTrackStats();
        Assert.assertNotNull(after);
        Assert.assertTrue("expected vmtool track stats cleared after shutdown", after.isEmpty());
    }
}
