package com.javasleuth.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Assert;
import org.junit.Test;

public class CrossClassLoaderFacadeContractTest {

    @Test
    public void facade_exposes_single_owner_for_reflection_contract() throws Exception {
        Class<?> facade = Class.forName("com.javasleuth.agent.CrossClassLoaderFacade");

        Method tryBeginAttach = facade.getDeclaredMethod("tryBeginAttachOrZero");
        Assert.assertTrue(Modifier.isStatic(tryBeginAttach.getModifiers()));
        Assert.assertEquals(long.class, tryBeginAttach.getReturnType());

        Method applyAgentArgsIfAbsent =
            facade.getDeclaredMethod("applyAgentArgsIfAbsent", long.class, String.class);
        Assert.assertTrue(Modifier.isStatic(applyAgentArgsIfAbsent.getModifiers()));
        Assert.assertEquals(boolean.class, applyAgentArgsIfAbsent.getReturnType());

        Method commitIsolatedClassLoader =
            facade.getDeclaredMethod("commitIsolatedClassLoader", long.class, ClassLoader.class);
        Assert.assertTrue(Modifier.isStatic(commitIsolatedClassLoader.getModifiers()));
        Assert.assertEquals(boolean.class, commitIsolatedClassLoader.getReturnType());

        Method failBestEffort = facade.getDeclaredMethod("failBestEffort", long.class, ClassLoader.class);
        Assert.assertTrue(Modifier.isStatic(failBestEffort.getModifiers()));
        Assert.assertEquals(void.class, failBestEffort.getReturnType());

        Method locateAgentContainerJar = facade.getDeclaredMethod("locateAgentContainerJar", Class.class);
        Assert.assertTrue(Modifier.isStatic(locateAgentContainerJar.getModifiers()));
        Assert.assertEquals(File.class, locateAgentContainerJar.getReturnType());

        Method invokeContainerEntrypoint =
            facade.getDeclaredMethod("invokeContainerEntrypoint", ClassLoader.class, String.class, String.class, Instrumentation.class);
        Assert.assertTrue(Modifier.isStatic(invokeContainerEntrypoint.getModifiers()));
        Assert.assertEquals(void.class, invokeContainerEntrypoint.getReturnType());

        Method isBootstrapBridgeAvailable = facade.getDeclaredMethod("isBootstrapBridgeAvailable");
        Assert.assertTrue(Modifier.isStatic(isBootstrapBridgeAvailable.getModifiers()));
        Assert.assertEquals(boolean.class, isBootstrapBridgeAvailable.getReturnType());
    }
}
