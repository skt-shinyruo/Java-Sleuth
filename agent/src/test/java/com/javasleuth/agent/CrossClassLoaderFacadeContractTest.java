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

        Method verifyBootstrapContract = facade.getDeclaredMethod("verifyBootstrapContract");
        Assert.assertTrue(Modifier.isStatic(verifyBootstrapContract.getModifiers()));
        Assert.assertEquals(CrossClassLoaderFacade.BootstrapContractCheck.class, verifyBootstrapContract.getReturnType());
    }

    @Test
    public void bootstrapContractCheck_reportsOkVersion() {
        CrossClassLoaderFacade.BootstrapContractCheck result = CrossClassLoaderFacade.BootstrapContractCheck.ok(1);

        Assert.assertTrue(result.userMessage(), result.isOk());
        Assert.assertEquals(CrossClassLoaderFacade.BootstrapContractCheck.Status.OK, result.getStatus());
        Assert.assertEquals(Integer.valueOf(1), result.getFoundVersion());
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("OK"));
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("version=1"));
    }

    @Test
    public void verifyBootstrapContract_rejectsBoxedReturnType() {
        CrossClassLoaderFacade.BootstrapContractCheck result =
            CrossClassLoaderFacade.verifyBootstrapContract(BoxedContractLifecycle.class);

        Assert.assertFalse(result.isOk());
        Assert.assertEquals(CrossClassLoaderFacade.BootstrapContractCheck.Status.BAD_RETURN_TYPE, result.getStatus());
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("BAD_RETURN_TYPE"));
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("java.lang.Integer"));
    }

    public static final class BoxedContractLifecycle {
        public static Integer contractVersion() {
            return Integer.valueOf(1);
        }
    }
}
