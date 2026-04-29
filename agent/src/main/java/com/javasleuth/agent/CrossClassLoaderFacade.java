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
    static final int MIN_BOOTSTRAP_CONTRACT_VERSION = 1;
    static final int MAX_BOOTSTRAP_CONTRACT_VERSION = 1;

    private static final String CONTAINER_ENTRYPOINT_CLASS = "com.javasleuth.container.SleuthAgentContainerEntrypoint";
    private static final String BOOTSTRAP_AGENT_LIFECYCLE_CLASS = "com.javasleuth.bootstrap.agent.AgentLifecycle";
    private static final String BOOTSTRAP_JAR_LOCATOR_CLASS = "com.javasleuth.bootstrap.util.JarLocator";
    private static final String BOOTSTRAP_SPY_API_CLASS = "com.javasleuth.bootstrap.spy.SleuthSpyAPI";

    private CrossClassLoaderFacade() {}

    static final class BootstrapContractCheck {
        enum Status {
            OK,
            MISSING_CLASS,
            MISSING_METHOD,
            BAD_RETURN_TYPE,
            INVOCATION_FAILED,
            INCOMPATIBLE_VERSION
        }

        private final Status status;
        private final Integer foundVersion;
        private final String detail;

        private BootstrapContractCheck(Status status, Integer foundVersion, String detail) {
            this.status = status;
            this.foundVersion = foundVersion;
            this.detail = detail;
        }

        static BootstrapContractCheck ok(int version) {
            return new BootstrapContractCheck(Status.OK, Integer.valueOf(version), null);
        }

        static BootstrapContractCheck failed(Status status, Integer foundVersion, String detail) {
            return new BootstrapContractCheck(status, foundVersion, detail);
        }

        boolean isOk() {
            return status == Status.OK;
        }

        Status getStatus() {
            return status;
        }

        Integer getFoundVersion() {
            return foundVersion;
        }

        String userMessage() {
            if (status == Status.OK) {
                return "Bootstrap bridge contract OK: version=" + foundVersion;
            }
            if (status == Status.INCOMPATIBLE_VERSION) {
                return "Bootstrap bridge contract mismatch: expected " + MIN_BOOTSTRAP_CONTRACT_VERSION
                    + ".." + MAX_BOOTSTRAP_CONTRACT_VERSION + ", found " + foundVersion;
            }
            if (status == Status.MISSING_METHOD) {
                return "Bootstrap bridge incomplete: missing AgentLifecycle.contractVersion()";
            }
            if (status == Status.MISSING_CLASS) {
                return "Bootstrap bridge unavailable: missing " + BOOTSTRAP_AGENT_LIFECYCLE_CLASS;
            }
            return "Bootstrap bridge contract check failed: " + status.name() + (detail != null ? " (" + detail + ")" : "");
        }
    }

    static BootstrapContractCheck verifyBootstrapContract() {
        try {
            Class<?> lifecycle = Class.forName(BOOTSTRAP_AGENT_LIFECYCLE_CLASS, false, null);
            return verifyBootstrapContract(lifecycle);
        } catch (ClassNotFoundException e) {
            return BootstrapContractCheck.failed(BootstrapContractCheck.Status.MISSING_CLASS, null, e.getMessage());
        } catch (Throwable t) {
            return BootstrapContractCheck.failed(BootstrapContractCheck.Status.INVOCATION_FAILED, null, t.getClass().getName());
        }
    }

    static BootstrapContractCheck verifyBootstrapContract(Class<?> lifecycle) {
        try {
            Method method = lifecycle.getMethod("contractVersion");
            if (method.getReturnType() != Integer.TYPE) {
                return BootstrapContractCheck.failed(
                    BootstrapContractCheck.Status.BAD_RETURN_TYPE,
                    null,
                    method.getReturnType().getName()
                );
            }
            Object result = method.invoke(null);
            if (!(result instanceof Number)) {
                return BootstrapContractCheck.failed(
                    BootstrapContractCheck.Status.BAD_RETURN_TYPE,
                    null,
                    String.valueOf(result)
                );
            }
            int version = ((Number) result).intValue();
            if (version < MIN_BOOTSTRAP_CONTRACT_VERSION || version > MAX_BOOTSTRAP_CONTRACT_VERSION) {
                return BootstrapContractCheck.failed(
                    BootstrapContractCheck.Status.INCOMPATIBLE_VERSION,
                    Integer.valueOf(version),
                    null
                );
            }
            return BootstrapContractCheck.ok(version);
        } catch (NoSuchMethodException e) {
            return BootstrapContractCheck.failed(BootstrapContractCheck.Status.MISSING_METHOD, null, e.getMessage());
        } catch (Throwable t) {
            return BootstrapContractCheck.failed(BootstrapContractCheck.Status.INVOCATION_FAILED, null, t.getClass().getName());
        }
    }

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
