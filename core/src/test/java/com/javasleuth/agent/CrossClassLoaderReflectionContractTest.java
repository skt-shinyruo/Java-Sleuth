package com.javasleuth.agent;

import java.io.File;
import java.lang.reflect.Method;
import org.junit.Assert;
import org.junit.Test;

/**
 * Cross-ClassLoader reflection contract test.
 *
 * <p>This test is intentionally reflection-based: it must fail when a class/method is renamed
 * or its signature changes, even if the refactor still compiles.</p>
 */
public class CrossClassLoaderReflectionContractTest {

    @Test
    public void contract_bootstrapBridgeClasses_and_reflectionSignatures_exist() throws Exception {
        Class<?> lifecycle = Class.forName("com.javasleuth.bootstrap.agent.AgentLifecycle");

        Method tryBeginAttach = lifecycle.getMethod("tryBeginAttach");
        Assert.assertEquals(long.class, tryBeginAttach.getReturnType());

        Method applyAgentArgsIfAbsent = lifecycle.getMethod("applyAgentArgsIfAbsent", long.class, String.class);
        Assert.assertEquals(boolean.class, applyAgentArgsIfAbsent.getReturnType());

        Method commitIsolatedClassLoader =
            lifecycle.getMethod("commitIsolatedClassLoader", long.class, ClassLoader.class);
        Assert.assertEquals(boolean.class, commitIsolatedClassLoader.getReturnType());

        Method failBestEffort = lifecycle.getMethod("failBestEffort", long.class, ClassLoader.class);
        Assert.assertEquals(void.class, failBestEffort.getReturnType());

        Method detachBestEffort = lifecycle.getMethod("detachBestEffort", ClassLoader.class);
        Assert.assertEquals(void.class, detachBestEffort.getReturnType());

        Class<?> jarLocator = Class.forName("com.javasleuth.bootstrap.util.JarLocator");

        Method locateAgentContainerJar = jarLocator.getMethod("locateAgentContainerJar", Class.class);
        Assert.assertEquals(File.class, locateAgentContainerJar.getReturnType());

        Method locateAgentCoreJar = jarLocator.getMethod("locateAgentCoreJar", Class.class);
        Assert.assertEquals(File.class, locateAgentCoreJar.getReturnType());

        // Required for bytecode enhancements that inject bootstrap callbacks.
        Class<?> spyApi = Class.forName("com.javasleuth.bootstrap.spy.SleuthSpyAPI");
        Assert.assertNotNull(spyApi);

        Method atEnter = spyApi.getMethod(
            "atEnter",
            String.class,
            Class.class,
            String.class,
            Object.class,
            Object[].class,
            long.class
        );
        Assert.assertEquals(void.class, atEnter.getReturnType());

        Method atExit = spyApi.getMethod(
            "atExit",
            String.class,
            Class.class,
            String.class,
            Object.class,
            Object[].class,
            Object.class,
            boolean.class,
            long.class,
            long.class
        );
        Assert.assertEquals(void.class, atExit.getReturnType());

        Method atExceptionExit = spyApi.getMethod(
            "atExceptionExit",
            String.class,
            Class.class,
            String.class,
            Object.class,
            Object[].class,
            Throwable.class,
            long.class,
            long.class
        );
        Assert.assertEquals(void.class, atExceptionExit.getReturnType());

        Method atBeforeInvoke = spyApi.getMethod(
            "atBeforeInvoke",
            String.class,
            Class.class,
            String.class,
            Object.class,
            long.class
        );
        Assert.assertEquals(void.class, atBeforeInvoke.getReturnType());

        Method atAfterInvoke = spyApi.getMethod(
            "atAfterInvoke",
            String.class,
            Class.class,
            String.class,
            Object.class,
            long.class
        );
        Assert.assertEquals(void.class, atAfterInvoke.getReturnType());

        Method atInvokeException = spyApi.getMethod(
            "atInvokeException",
            String.class,
            Class.class,
            String.class,
            Object.class,
            Throwable.class,
            long.class
        );
        Assert.assertEquals(void.class, atInvokeException.getReturnType());

        Method onConstructed = spyApi.getMethod("onConstructed", String.class, Object.class);
        Assert.assertEquals(void.class, onConstructed.getReturnType());
    }
}
