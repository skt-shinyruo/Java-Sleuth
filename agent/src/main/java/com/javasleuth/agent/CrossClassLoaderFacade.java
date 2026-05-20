package com.javasleuth.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

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
    static final int MIN_CONTAINER_CONTRACT_VERSION = 1;
    static final int MAX_CONTAINER_CONTRACT_VERSION = 1;

    private static final String CONTAINER_ENTRYPOINT_CLASS = "com.javasleuth.container.SleuthAgentContainerEntrypoint";
    private static final String BOOTSTRAP_AGENT_LIFECYCLE_CLASS = "com.javasleuth.bootstrap.agent.AgentLifecycle";
    private static final String BOOTSTRAP_JAR_LOCATOR_CLASS = "com.javasleuth.bootstrap.util.JarLocator";
    private static final String BOOTSTRAP_SPY_API_CLASS = "com.javasleuth.bootstrap.spy.SleuthSpyAPI";

    private static final ContractClass BOOTSTRAP_AGENT_LIFECYCLE = new ContractClass(
        "bootstrap bridge",
        BOOTSTRAP_AGENT_LIFECYCLE_CLASS,
        new ContractMethod[] {
            new ContractMethod("contractVersion", int.class),
            new ContractMethod("tryBeginAttach", long.class),
            new ContractMethod("applyAgentArgsIfAbsent", boolean.class, long.class, String.class),
            new ContractMethod("commitIsolatedClassLoader", boolean.class, long.class, ClassLoader.class),
            new ContractMethod("failBestEffort", void.class, long.class, ClassLoader.class),
            new ContractMethod("detachBestEffort", void.class, ClassLoader.class)
        }
    );
    private static final ContractClass BOOTSTRAP_JAR_LOCATOR = new ContractClass(
        "bootstrap bridge",
        BOOTSTRAP_JAR_LOCATOR_CLASS,
        new ContractMethod[] {
            new ContractMethod("locateAgentJar", File.class, Class.class),
            new ContractMethod("locateAgentContainerJar", File.class, Class.class)
        }
    );
    private static final ContractClass BOOTSTRAP_SPY_API = new ContractClass(
        "bootstrap bridge",
        BOOTSTRAP_SPY_API_CLASS,
        new ContractMethod[] {
            new ContractMethod("atEnter", void.class, String.class, Class.class, String.class, Object.class, Object[].class, long.class),
            new ContractMethod(
                "atExit",
                void.class,
                String.class,
                Class.class,
                String.class,
                Object.class,
                Object[].class,
                Object.class,
                boolean.class,
                long.class,
                long.class
            ),
            new ContractMethod(
                "atExceptionExit",
                void.class,
                String.class,
                Class.class,
                String.class,
                Object.class,
                Object[].class,
                Throwable.class,
                long.class,
                long.class
            ),
            new ContractMethod("atBeforeInvoke", void.class, String.class, Class.class, String.class, Object.class, long.class),
            new ContractMethod("atAfterInvoke", void.class, String.class, Class.class, String.class, Object.class, long.class),
            new ContractMethod("atInvokeException", void.class, String.class, Class.class, String.class, Object.class, Throwable.class, long.class),
            new ContractMethod("onConstructed", void.class, String.class, Object.class)
        }
    );
    private static final ContractClass CONTAINER_ENTRYPOINT = new ContractClass(
        "isolated container",
        CONTAINER_ENTRYPOINT_CLASS,
        new ContractMethod[] {
            new ContractMethod("contractVersion", int.class),
            new ContractMethod(ENTRY_AGENTMAIN, void.class, String.class, Instrumentation.class),
            new ContractMethod(ENTRY_PREMAIN, void.class, String.class, Instrumentation.class)
        }
    );

    private CrossClassLoaderFacade() {}

    static final class ContractCheck {
        enum Status {
            OK,
            MISSING_CLASS,
            MISSING_METHOD,
            BAD_RETURN_TYPE,
            INVOCATION_FAILED,
            INCOMPATIBLE_VERSION
        }

        private final String contractName;
        private final Status status;
        private final Integer foundVersion;
        private final String detail;

        private ContractCheck(String contractName, Status status, Integer foundVersion, String detail) {
            this.contractName = contractName;
            this.status = status;
            this.foundVersion = foundVersion;
            this.detail = detail;
        }

        static ContractCheck ok(String contractName, int version) {
            return new ContractCheck(contractName, Status.OK, Integer.valueOf(version), null);
        }

        static ContractCheck failed(String contractName, Status status, Integer foundVersion, String detail) {
            return new ContractCheck(contractName, status, foundVersion, detail);
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
                return displayName() + " contract OK: version=" + foundVersion;
            }
            if (status == Status.INCOMPATIBLE_VERSION) {
                return displayName() + " contract mismatch: " + detail;
            }
            return displayName() + " contract failed: " + status.name() + (detail != null ? " (" + detail + ")" : "");
        }

        private String displayName() {
            return contractName != null && !contractName.trim().isEmpty() ? contractName : "cross-classloader";
        }
    }

    static ContractCheck verifyBootstrapContract() {
        try {
            Class<?> lifecycle = loadClass(BOOTSTRAP_AGENT_LIFECYCLE, null);
            Class<?> jarLocator = loadClass(BOOTSTRAP_JAR_LOCATOR, null);
            Class<?> spyApi = loadClass(BOOTSTRAP_SPY_API, null);
            return verifyBootstrapContract(lifecycle, jarLocator, spyApi);
        } catch (ClassNotFoundException e) {
            return ContractCheck.failed(
                "bootstrap bridge",
                ContractCheck.Status.MISSING_CLASS,
                null,
                "missing stable class " + e.getMessage()
            );
        } catch (Throwable t) {
            return ContractCheck.failed("bootstrap bridge", ContractCheck.Status.INVOCATION_FAILED, null, describeThrowable(t));
        }
    }

    static ContractCheck verifyBootstrapContract(Class<?> lifecycle) {
        return verifyBootstrapContract(lifecycle, null, null);
    }

    static ContractCheck verifyBootstrapContract(Class<?> lifecycle, Class<?> jarLocator, Class<?> spyApi) {
        ContractCheck check = verifyContractClasses(
            "bootstrap bridge",
            new ContractClass[] {BOOTSTRAP_AGENT_LIFECYCLE, BOOTSTRAP_JAR_LOCATOR, BOOTSTRAP_SPY_API},
            new Class<?>[] {lifecycle, jarLocator, spyApi}
        );
        if (!check.isOk()) {
            return check;
        }
        return verifyVersion(
            "bootstrap bridge",
            lifecycle,
            BOOTSTRAP_AGENT_LIFECYCLE.method("contractVersion"),
            MIN_BOOTSTRAP_CONTRACT_VERSION,
            MAX_BOOTSTRAP_CONTRACT_VERSION
        );
    }

    static ContractCheck verifyContainerEntrypointContract(ClassLoader isolated) {
        if (isolated == null) {
            return ContractCheck.failed(
                "isolated container",
                ContractCheck.Status.MISSING_CLASS,
                null,
                "isolated ClassLoader is null"
            );
        }
        try {
            Class<?> entry = loadClass(CONTAINER_ENTRYPOINT, isolated);
            return verifyContainerEntrypointContract(entry);
        } catch (ClassNotFoundException e) {
            return ContractCheck.failed(
                "isolated container",
                ContractCheck.Status.MISSING_CLASS,
                null,
                "missing stable class " + e.getMessage()
            );
        } catch (Throwable t) {
            return ContractCheck.failed("isolated container", ContractCheck.Status.INVOCATION_FAILED, null, describeThrowable(t));
        }
    }

    static ContractCheck verifyContainerEntrypointContract(Class<?> entrypoint) {
        ContractCheck check = verifyContractClasses(
            "isolated container",
            new ContractClass[] {CONTAINER_ENTRYPOINT},
            new Class<?>[] {entrypoint}
        );
        if (!check.isOk()) {
            return check;
        }
        return verifyVersion(
            "isolated container",
            entrypoint,
            CONTAINER_ENTRYPOINT.method("contractVersion"),
            MIN_CONTAINER_CONTRACT_VERSION,
            MAX_CONTAINER_CONTRACT_VERSION
        );
    }

    static long tryBeginAttachOrZero() {
        try {
            Object result = invokeLifecycleMethod(BOOTSTRAP_AGENT_LIFECYCLE.method("tryBeginAttach"));
            return (result instanceof Number) ? ((Number) result).longValue() : 0L;
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    static boolean applyAgentArgsIfAbsent(long sessionId, String agentArgs) {
        try {
            Object result = invokeLifecycleMethod(
                BOOTSTRAP_AGENT_LIFECYCLE.method("applyAgentArgsIfAbsent"),
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
                BOOTSTRAP_AGENT_LIFECYCLE.method("commitIsolatedClassLoader"),
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
                BOOTSTRAP_AGENT_LIFECYCLE.method("failBestEffort"),
                Long.valueOf(sessionId),
                loaderOrNull
            );
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    static File locateAgentContainerJar(Class<?> anchor) {
        try {
            Class<?> jarLocator = loadClass(BOOTSTRAP_JAR_LOCATOR, null);
            Method method = requireMethod(jarLocator, BOOTSTRAP_JAR_LOCATOR.method("locateAgentContainerJar"));
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
        ContractCheck check = verifyContainerEntrypointContract(isolated);
        if (!check.isOk()) {
            throw new IllegalStateException("Java-Sleuth: " + check.userMessage() + "; aborting container startup");
        }
        Class<?> entry = loadClass(CONTAINER_ENTRYPOINT, isolated);
        Method method = requireMethod(entry, CONTAINER_ENTRYPOINT.method(methodName));
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

    private static Object invokeLifecycleMethod(ContractMethod contractMethod, Object... args) throws Exception {
        Class<?> lifecycle = loadClass(BOOTSTRAP_AGENT_LIFECYCLE, null);
        Method method = requireMethod(lifecycle, contractMethod);
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

    private static Class<?> loadClass(ContractClass contractClass, ClassLoader loader) throws ClassNotFoundException {
        try {
            return Class.forName(contractClass.binaryName, false, loader);
        } catch (ClassNotFoundException e) {
            throw e;
        }
    }

    private static ContractCheck verifyContractClasses(String contractName, ContractClass[] expected, Class<?>[] actual) {
        if (expected == null || actual == null || expected.length != actual.length) {
            return ContractCheck.failed(contractName, ContractCheck.Status.INVOCATION_FAILED, null, "invalid contract verifier input");
        }
        for (int i = 0; i < expected.length; i++) {
            ContractClass expectedClass = expected[i];
            Class<?> actualClass = actual[i];
            if (actualClass == null) {
                return ContractCheck.failed(
                    contractName,
                    ContractCheck.Status.MISSING_CLASS,
                    null,
                    "missing stable class " + expectedClass.binaryName
                );
            }
            for (int j = 0; j < expectedClass.methods.length; j++) {
                ContractMethod expectedMethod = expectedClass.methods[j];
                try {
                    requireMethod(actualClass, expectedMethod);
                } catch (NoSuchMethodException e) {
                    return ContractCheck.failed(
                        contractName,
                        ContractCheck.Status.MISSING_METHOD,
                        null,
                        expectedClass.binaryName + "." + expectedMethod.signature()
                    );
                } catch (BadReturnTypeException e) {
                    return ContractCheck.failed(
                        contractName,
                        ContractCheck.Status.BAD_RETURN_TYPE,
                        null,
                        expectedClass.binaryName + "." + expectedMethod.signature() + " returned " + typeName(e.actualReturnType)
                    );
                } catch (Throwable t) {
                    return ContractCheck.failed(
                        contractName,
                        ContractCheck.Status.INVOCATION_FAILED,
                        null,
                        expectedClass.binaryName + "." + expectedMethod.signature() + " failed validation: " + describeThrowable(t)
                    );
                }
            }
        }
        return ContractCheck.ok(contractName, 0);
    }

    private static ContractCheck verifyVersion(
        String contractName,
        Class<?> owner,
        ContractMethod versionMethod,
        int minVersion,
        int maxVersion
    ) {
        try {
            Method method = requireMethod(owner, versionMethod);
            Object result = method.invoke(null);
            if (!(result instanceof Number)) {
                return ContractCheck.failed(
                    contractName,
                    ContractCheck.Status.BAD_RETURN_TYPE,
                    null,
                    versionMethod.signature() + " returned non-number " + String.valueOf(result)
                );
            }
            int version = ((Number) result).intValue();
            if (version < minVersion || version > maxVersion) {
                return ContractCheck.failed(
                    contractName,
                    ContractCheck.Status.INCOMPATIBLE_VERSION,
                    Integer.valueOf(version),
                    "expected version " + minVersion + ".." + maxVersion + ", found " + version
                );
            }
            return ContractCheck.ok(contractName, version);
        } catch (NoSuchMethodException e) {
            return ContractCheck.failed(contractName, ContractCheck.Status.MISSING_METHOD, null, versionMethod.signature());
        } catch (BadReturnTypeException e) {
            return ContractCheck.failed(
                contractName,
                ContractCheck.Status.BAD_RETURN_TYPE,
                null,
                versionMethod.signature() + " returned " + typeName(e.actualReturnType)
            );
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ContractCheck.failed(contractName, ContractCheck.Status.INVOCATION_FAILED, null, describeThrowable(cause));
        } catch (Throwable t) {
            return ContractCheck.failed(contractName, ContractCheck.Status.INVOCATION_FAILED, null, describeThrowable(t));
        }
    }

    private static Method requireMethod(Class<?> owner, ContractMethod expected)
        throws NoSuchMethodException, BadReturnTypeException {
        Method method = owner.getMethod(expected.name, expected.parameterTypes);
        if (method.getReturnType() != expected.returnType) {
            throw new BadReturnTypeException(method.getReturnType());
        }
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(expected.name + " is not static");
        }
        return method;
    }

    private static String describeThrowable(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String message = t.getMessage();
        return t.getClass().getName() + (message != null && !message.trim().isEmpty() ? ": " + message : "");
    }

    private static String typeName(Class<?> type) {
        return type != null ? type.getName() : "null";
    }

    private static final class ContractClass {
        private final String contractName;
        private final String binaryName;
        private final ContractMethod[] methods;

        private ContractClass(String contractName, String binaryName, ContractMethod[] methods) {
            this.contractName = contractName;
            this.binaryName = binaryName;
            this.methods = methods != null ? methods : new ContractMethod[0];
        }

        private ContractMethod method(String name) {
            for (int i = 0; i < methods.length; i++) {
                ContractMethod method = methods[i];
                if (method.name.equals(name)) {
                    return method;
                }
            }
            throw new IllegalArgumentException(contractName + " contract method not declared: " + binaryName + "." + name);
        }
    }

    private static final class ContractMethod {
        private final String name;
        private final Class<?> returnType;
        private final Class<?>[] parameterTypes;

        private ContractMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
            this.name = name;
            this.returnType = returnType;
            this.parameterTypes = parameterTypes != null ? parameterTypes : new Class<?>[0];
        }

        private String signature() {
            StringBuilder sb = new StringBuilder();
            sb.append(name).append('(');
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(typeName(parameterTypes[i]));
            }
            sb.append(") : ").append(typeName(returnType));
            return sb.toString();
        }
    }

    private static final class BadReturnTypeException extends Exception {
        private final Class<?> actualReturnType;

        private BadReturnTypeException(Class<?> actualReturnType) {
            this.actualReturnType = actualReturnType;
        }
    }
}
