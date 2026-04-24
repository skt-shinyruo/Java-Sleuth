package com.javasleuth.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

/**
 * Single owner of the thin-agent cross-classloader reflection contract.
 *
 * <p>This keeps all binary class names and reflective method lookups in one place so the bootstrap
 * agent does not scatter classloader-boundary details across its startup flow.</p>
 */
final class CrossClassLoaderFacade {
    static final String ENTRY_AGENTMAIN = "agentmain";
    static final String ENTRY_PREMAIN = "premain";

    private static final String CONTAINER_ENTRYPOINT_CLASS = "com.javasleuth.container.SleuthAgentContainerEntrypoint";
    private static final String BOOTSTRAP_AGENT_LIFECYCLE_CLASS = "com.javasleuth.bootstrap.agent.AgentLifecycle";
    private static final String BOOTSTRAP_JAR_LOCATOR_CLASS = "com.javasleuth.bootstrap.util.JarLocator";
    private static final String BOOTSTRAP_SPY_API_CLASS = "com.javasleuth.bootstrap.spy.SleuthSpyAPI";

    private CrossClassLoaderFacade() {}

    static long tryBeginAttachOrZero() {
        try {
            Object result = invokeLifecycleMethod("tryBeginAttach", new Class<?>[0]);
            return (result instanceof Number) ? ((Number) result).longValue() : 0L;
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    static boolean applyAgentArgsIfAbsent(long sessionId, String agentArgs) {
        try {
            Object result = invokeLifecycleMethod(
                "applyAgentArgsIfAbsent",
                new Class<?>[] {long.class, String.class},
                Long.valueOf(sessionId),
                agentArgs
            );
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignore) {
            return false;
        }
    }

    static boolean commitIsolatedClassLoader(long sessionId, ClassLoader loader) {
        try {
            Object result = invokeLifecycleMethod(
                "commitIsolatedClassLoader",
                new Class<?>[] {long.class, ClassLoader.class},
                Long.valueOf(sessionId),
                loader
            );
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignore) {
            return false;
        }
    }

    static void failBestEffort(long sessionId, ClassLoader loaderOrNull) {
        try {
            invokeLifecycleMethod(
                "failBestEffort",
                new Class<?>[] {long.class, ClassLoader.class},
                Long.valueOf(sessionId),
                loaderOrNull
            );
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    static File locateAgentContainerJar(Class<?> anchor) {
        try {
            Class<?> jarLocator = Class.forName(BOOTSTRAP_JAR_LOCATOR_CLASS, false, null);
            Method method = jarLocator.getMethod("locateAgentContainerJar", Class.class);
            Object result = method.invoke(null, anchor != null ? anchor : CrossClassLoaderFacade.class);
            return (result instanceof File) ? (File) result : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    static void invokeContainerEntrypoint(ClassLoader isolated, String phase, String agentArgs, Instrumentation inst) throws Exception {
        if (isolated == null) {
            throw new IllegalArgumentException("isolated");
        }
        String methodName = ENTRY_PREMAIN.equals(phase) ? ENTRY_PREMAIN : ENTRY_AGENTMAIN;
        Class<?> entry = Class.forName(CONTAINER_ENTRYPOINT_CLASS, true, isolated);
        Method method = entry.getMethod(methodName, String.class, Instrumentation.class);
        method.invoke(null, agentArgs, inst);
    }

    static boolean isBootstrapBridgeAvailable() {
        if (!isBootstrapClassAvailable(BOOTSTRAP_AGENT_LIFECYCLE_CLASS)) {
            return false;
        }
        if (!isBootstrapClassAvailable(BOOTSTRAP_JAR_LOCATOR_CLASS)) {
            return false;
        }
        return isBootstrapClassAvailable(BOOTSTRAP_SPY_API_CLASS);
    }

    private static Object invokeLifecycleMethod(String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Class<?> lifecycle = Class.forName(BOOTSTRAP_AGENT_LIFECYCLE_CLASS, false, null);
        Method method = lifecycle.getMethod(methodName, paramTypes);
        return method.invoke(null, args);
    }

    private static boolean isBootstrapClassAvailable(String binaryName) {
        if (binaryName == null || binaryName.trim().isEmpty()) {
            return false;
        }
        try {
            Class<?> c = Class.forName(binaryName, false, null);
            return c != null && c.getClassLoader() == null;
        } catch (Throwable ignore) {
            return false;
        }
    }
}
