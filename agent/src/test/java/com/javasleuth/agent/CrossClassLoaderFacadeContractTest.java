package com.javasleuth.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
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
        Assert.assertEquals(CrossClassLoaderFacade.ContractCheck.class, verifyBootstrapContract.getReturnType());

        Method verifyContainerEntrypointContract =
            facade.getDeclaredMethod("verifyContainerEntrypointContract", ClassLoader.class);
        Assert.assertTrue(Modifier.isStatic(verifyContainerEntrypointContract.getModifiers()));
        Assert.assertEquals(CrossClassLoaderFacade.ContractCheck.class, verifyContainerEntrypointContract.getReturnType());
    }

    @Test
    public void bootstrapContractCheck_reportsOkVersion() {
        CrossClassLoaderFacade.ContractCheck result =
            CrossClassLoaderFacade.ContractCheck.ok("bootstrap bridge", 1);

        Assert.assertTrue(result.userMessage(), result.isOk());
        Assert.assertEquals(CrossClassLoaderFacade.ContractCheck.Status.OK, result.getStatus());
        Assert.assertEquals(Integer.valueOf(1), result.getFoundVersion());
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("OK"));
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("version=1"));
    }

    @Test
    public void verifyBootstrapContract_rejectsBoxedReturnType() {
        CrossClassLoaderFacade.ContractCheck result =
            CrossClassLoaderFacade.verifyBootstrapContract(BoxedContractLifecycle.class, CompleteJarLocator.class, CompleteSpyApi.class);

        Assert.assertFalse(result.isOk());
        Assert.assertEquals(CrossClassLoaderFacade.ContractCheck.Status.BAD_RETURN_TYPE, result.getStatus());
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("BAD_RETURN_TYPE"));
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("contractVersion() : int"));
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("java.lang.Integer"));
    }

    @Test
    public void verifyBootstrapContract_rejectsMissingStableClassWithDiagnostic() {
        CrossClassLoaderFacade.ContractCheck result =
            CrossClassLoaderFacade.verifyBootstrapContract(CompleteLifecycle.class, null, CompleteSpyApi.class);

        Assert.assertFalse(result.isOk());
        Assert.assertEquals(CrossClassLoaderFacade.ContractCheck.Status.MISSING_CLASS, result.getStatus());
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("MISSING_CLASS"));
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("com.javasleuth.bootstrap.util.JarLocator"));
        Assert.assertFalse(result.userMessage(), result.userMessage().contains("ClassNotFoundException"));
    }

    @Test
    public void verifyBootstrapContract_rejectsMethodSignatureChangeWithExpectedSignature() {
        CrossClassLoaderFacade.ContractCheck result = CrossClassLoaderFacade.verifyBootstrapContract(
            LifecycleWithChangedTryBeginAttach.class,
            CompleteJarLocator.class,
            CompleteSpyApi.class
        );

        Assert.assertFalse(result.isOk());
        Assert.assertEquals(CrossClassLoaderFacade.ContractCheck.Status.MISSING_METHOD, result.getStatus());
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("MISSING_METHOD"));
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("tryBeginAttach() : long"));
        Assert.assertFalse(result.userMessage(), result.userMessage().contains("NoSuchMethodException"));
    }

    @Test
    public void verifyBootstrapContract_rejectsVersionMismatchBeforeRuntimeCalls() {
        CrossClassLoaderFacade.ContractCheck result =
            CrossClassLoaderFacade.verifyBootstrapContract(OldVersionLifecycle.class, CompleteJarLocator.class, CompleteSpyApi.class);

        Assert.assertFalse(result.isOk());
        Assert.assertEquals(CrossClassLoaderFacade.ContractCheck.Status.INCOMPATIBLE_VERSION, result.getStatus());
        Assert.assertEquals(Integer.valueOf(0), result.getFoundVersion());
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("expected version 1..1"));
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("found 0"));
    }

    @Test
    public void verifyContainerEntrypointContract_rejectsMethodSignatureChange() {
        CrossClassLoaderFacade.ContractCheck result =
            CrossClassLoaderFacade.verifyContainerEntrypointContract(BadContainerEntrypoint.class);

        Assert.assertFalse(result.isOk());
        Assert.assertEquals(CrossClassLoaderFacade.ContractCheck.Status.MISSING_METHOD, result.getStatus());
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("MISSING_METHOD"));
        Assert.assertTrue(result.userMessage(), result.userMessage().contains(
            "agentmain(java.lang.String, java.lang.instrument.Instrumentation) : void"
        ));
    }

    @Test
    public void verifyContainerEntrypointContract_rejectsVersionMismatch() {
        CrossClassLoaderFacade.ContractCheck result =
            CrossClassLoaderFacade.verifyContainerEntrypointContract(OldVersionContainerEntrypoint.class);

        Assert.assertFalse(result.isOk());
        Assert.assertEquals(CrossClassLoaderFacade.ContractCheck.Status.INCOMPATIBLE_VERSION, result.getStatus());
        Assert.assertEquals(Integer.valueOf(0), result.getFoundVersion());
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("isolated container contract mismatch"));
        Assert.assertTrue(result.userMessage(), result.userMessage().contains("expected version 1..1"));
    }

    @Test
    public void verifyContainerEntrypointContract_acceptsCompleteEntrypoint() {
        CrossClassLoaderFacade.ContractCheck result =
            CrossClassLoaderFacade.verifyContainerEntrypointContract(CompleteContainerEntrypoint.class);

        Assert.assertTrue(result.userMessage(), result.isOk());
        Assert.assertEquals(CrossClassLoaderFacade.ContractCheck.Status.OK, result.getStatus());
        Assert.assertEquals(Integer.valueOf(1), result.getFoundVersion());
    }

    @Test
    public void invokeContainerEntrypoint_rejectsMissingContainerClassWithDiagnostic() throws Exception {
        URLClassLoader isolated = new URLClassLoader(new URL[0], null);
        try {
            CrossClassLoaderFacade.invokeContainerEntrypoint(
                isolated,
                CrossClassLoaderFacade.ENTRY_AGENTMAIN,
                null,
                null
            );
            Assert.fail("expected missing container contract to abort startup");
        } catch (IllegalStateException expected) {
            String message = String.valueOf(expected.getMessage());
            Assert.assertTrue(message, message.contains("isolated container contract failed"));
            Assert.assertTrue(message, message.contains("MISSING_CLASS"));
            Assert.assertTrue(message, message.contains("com.javasleuth.container.SleuthAgentContainerEntrypoint"));
        } finally {
            isolated.close();
        }
    }

    public static final class BoxedContractLifecycle {
        public static Integer contractVersion() {
            return Integer.valueOf(1);
        }
    }

    public static final class OldVersionLifecycle {
        public static int contractVersion() {
            return 0;
        }

        public static long tryBeginAttach() {
            return 1L;
        }

        public static boolean applyAgentArgsIfAbsent(long sessionId, String agentArgs) {
            return true;
        }

        public static boolean commitIsolatedClassLoader(long sessionId, ClassLoader loader) {
            return true;
        }

        public static void failBestEffort(long sessionId, ClassLoader loaderOrNull) {}

        public static void detachBestEffort(ClassLoader selfClassLoader) {}
    }

    public static final class CompleteLifecycle {
        public static int contractVersion() {
            return 1;
        }

        public static long tryBeginAttach() {
            return 1L;
        }

        public static boolean applyAgentArgsIfAbsent(long sessionId, String agentArgs) {
            return true;
        }

        public static boolean commitIsolatedClassLoader(long sessionId, ClassLoader loader) {
            return true;
        }

        public static void failBestEffort(long sessionId, ClassLoader loaderOrNull) {}

        public static void detachBestEffort(ClassLoader selfClassLoader) {}
    }

    public static final class LifecycleWithChangedTryBeginAttach {
        public static int contractVersion() {
            return 1;
        }

        public static long tryBeginAttach(String ignored) {
            return 1L;
        }

        public static boolean applyAgentArgsIfAbsent(long sessionId, String agentArgs) {
            return true;
        }

        public static boolean commitIsolatedClassLoader(long sessionId, ClassLoader loader) {
            return true;
        }

        public static void failBestEffort(long sessionId, ClassLoader loaderOrNull) {}

        public static void detachBestEffort(ClassLoader selfClassLoader) {}
    }

    public static final class CompleteJarLocator {
        public static File locateAgentJar(Class<?> anchor) {
            return null;
        }

        public static File locateAgentContainerJar(Class<?> anchor) {
            return null;
        }
    }

    public static final class CompleteSpyApi {
        public static void atEnter(
            String listenerId,
            Class<?> clazz,
            String methodInfo,
            Object target,
            Object[] args,
            long startNanos
        ) {}

        public static void atExit(
            String listenerId,
            Class<?> clazz,
            String methodInfo,
            Object target,
            Object[] args,
            Object returnObject,
            boolean returnCaptured,
            long startNanos,
            long durationNanos
        ) {}

        public static void atExceptionExit(
            String listenerId,
            Class<?> clazz,
            String methodInfo,
            Object target,
            Object[] args,
            Throwable throwable,
            long startNanos,
            long durationNanos
        ) {}

        public static void atBeforeInvoke(
            String listenerId,
            Class<?> clazz,
            String invokeInfo,
            Object target,
            long whenNanos
        ) {}

        public static void atAfterInvoke(
            String listenerId,
            Class<?> clazz,
            String invokeInfo,
            Object target,
            long whenNanos
        ) {}

        public static void atInvokeException(
            String listenerId,
            Class<?> clazz,
            String invokeInfo,
            Object target,
            Throwable throwable,
            long whenNanos
        ) {}

        public static void onConstructed(String listenerId, Object instance) {}
    }

    public static final class BadContainerEntrypoint {
        public static int contractVersion() {
            return 1;
        }

        public static void agentmain(String agentArgs) {}

        public static void premain(String agentArgs, Instrumentation inst) {}
    }

    public static final class OldVersionContainerEntrypoint {
        public static int contractVersion() {
            return 0;
        }

        public static void agentmain(String agentArgs, Instrumentation inst) {}

        public static void premain(String agentArgs, Instrumentation inst) {}
    }

    public static final class CompleteContainerEntrypoint {
        public static int contractVersion() {
            return 1;
        }

        public static void agentmain(String agentArgs, Instrumentation inst) {}

        public static void premain(String agentArgs, Instrumentation inst) {}
    }
}
