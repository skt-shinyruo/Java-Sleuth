package com.javasleuth.container;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import org.junit.Assert;
import org.junit.Test;

/**
 * Reflection-based contract test for the container entrypoint.
 *
 * <p>The thin bootstrap agent reflectively invokes these methods from an isolated classloader.</p>
 */
public class ContainerEntrypointContractTest {

    @Test
    public void contract_entrypoint_hasStableAgentmainAndPremainSignatures() throws Exception {
        Class<?> entry = Class.forName("com.javasleuth.container.SleuthAgentContainerEntrypoint");

        Method contractVersion = entry.getMethod("contractVersion");
        Assert.assertEquals(int.class, contractVersion.getReturnType());
        Assert.assertEquals(Integer.valueOf(1), contractVersion.invoke(null));

        Method agentmain = entry.getMethod("agentmain", String.class, Instrumentation.class);
        Assert.assertEquals(void.class, agentmain.getReturnType());

        Method premain = entry.getMethod("premain", String.class, Instrumentation.class);
        Assert.assertEquals(void.class, premain.getReturnType());
    }
}
