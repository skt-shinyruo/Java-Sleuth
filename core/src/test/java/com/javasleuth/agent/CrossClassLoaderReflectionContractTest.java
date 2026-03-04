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
        Class<?> traceInterceptor = Class.forName("com.javasleuth.bootstrap.monitor.TraceInterceptor");
        Assert.assertNotNull(traceInterceptor);
    }
}

