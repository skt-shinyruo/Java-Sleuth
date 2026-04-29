package com.javasleuth.core.agent.runtime;

/**
 * Bootstrap bridge availability checks.
 *
 * <p>In the real agent runtime, core is loaded by an isolated {@code URLClassLoader} with {@code parent=null}
 * (delegates directly to the BootstrapClassLoader). In that mode, any enhancer that injects calls to
 * {@code com.javasleuth.bootstrap.*} MUST ensure those classes are bootstrap-visible, otherwise the enhanced
 * application bytecode may throw {@code NoClassDefFoundError}/{@code LinkageError} at runtime.</p>
 *
 * <p>In unit tests (core loaded by the system classloader), we allow a relaxed check so tests can run without
 * attaching a real agent.</p>
 */
public final class BootstrapBridge {
    public static final String AGENT_LIFECYCLE = "com.javasleuth.bootstrap.agent.AgentLifecycle";
    public static final String SPY_API = "com.javasleuth.bootstrap.spy.SleuthSpyAPI";
    public static final String TRACE_INTERCEPTOR = "com.javasleuth.bootstrap.monitor.TraceInterceptor";
    public static final String WATCH_INTERCEPTOR = "com.javasleuth.bootstrap.monitor.WatchInterceptor";
    public static final String MONITOR_INTERCEPTOR = "com.javasleuth.bootstrap.monitor.MonitorInterceptor";
    public static final String TT_INTERCEPTOR = "com.javasleuth.bootstrap.monitor.TtInterceptor";
    public static final String STACK_INTERCEPTOR = "com.javasleuth.bootstrap.monitor.StackInterceptor";
    public static final String VMTOOL_INTERCEPTOR = "com.javasleuth.bootstrap.monitor.VmToolInterceptor";
    public static final int MIN_CONTRACT_VERSION = 1;
    public static final int MAX_CONTRACT_VERSION = 1;

    private BootstrapBridge() {}

    private enum ContractStatus {
        OK,
        MISSING_CLASS,
        MISSING_METHOD,
        BAD_RETURN_TYPE,
        INVOCATION_FAILED,
        INCOMPATIBLE_VERSION
    }

    private static final class ContractCheck {
        private final ContractStatus status;
        private final int version;
        private final String visibility;
        private final String detail;

        private ContractCheck(ContractStatus status, int version, String visibility, String detail) {
            this.status = status;
            this.version = version;
            this.visibility = visibility;
            this.detail = detail;
        }

        private static ContractCheck ok(int version, String visibility) {
            return new ContractCheck(ContractStatus.OK, version, visibility, null);
        }

        private static ContractCheck failed(ContractStatus status, int version, String detail) {
            return new ContractCheck(status, version, null, detail);
        }
    }

    /**
     * Strict mode is enabled when core is running in the isolated classloader (parent == null).
     *
     * <p>In strict mode we require bootstrap-visible classes to avoid crashing the target application at runtime.</p>
     */
    public static boolean isStrictMode() {
        ClassLoader self = BootstrapBridge.class.getClassLoader();
        if (self == null) {
            // Already in bootstrap.
            return true;
        }
        return self.getParent() == null;
    }

    public static boolean isBootstrapVisible(String binaryClassName) {
        if (binaryClassName == null || binaryClassName.trim().isEmpty()) {
            return false;
        }
        try {
            Class<?> c = Class.forName(binaryClassName, false, null);
            return c != null && c.getClassLoader() == null;
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static boolean isVisibleFrom(String binaryClassName, ClassLoader loader) {
        if (binaryClassName == null || binaryClassName.trim().isEmpty()) {
            return false;
        }
        try {
            Class<?> c = Class.forName(binaryClassName, false, loader);
            return c != null;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Check whether an enhancer that depends on {@code requiredBootstrapClass} can be safely enabled for a target loader.
     *
     * <p>Strict mode: require bootstrap visibility.</p>
     * <p>Relaxed mode (tests): require visibility from the target loader (or current loader as a fallback).</p>
     */
    public static boolean canEnableEnhancement(String requiredBootstrapClass, ClassLoader targetLoader) {
        if (isStrictMode()) {
            return isBootstrapVisible(requiredBootstrapClass);
        }
        ClassLoader effective = targetLoader != null ? targetLoader : BootstrapBridge.class.getClassLoader();
        return isVisibleFrom(requiredBootstrapClass, effective);
    }

    public static String formatDisabledMessage(String feature, String requiredBootstrapClass) {
        String f = feature == null || feature.trim().isEmpty() ? "enhancement" : feature.trim();
        String required = requiredBootstrapClass == null ? "<unknown>" : requiredBootstrapClass;
        if (isStrictMode()) {
            return "Bootstrap bridge unavailable: BootstrapClassLoader cannot load " + required
                + ". For safety, '" + f + "' is disabled (prevents NoClassDefFoundError/LinkageError in target app).";
        }
        return "Bootstrap bridge unavailable for '" + f + "': cannot load " + required + " from current classloader.";
    }

    public static String describeStatus() {
        boolean strict = isStrictMode();
        boolean ok = canEnableEnhancement(SPY_API, null);
        if (ok) {
            return strict ? "OK (bootstrap-visible)" : "OK (classpath-visible)";
        }
        return strict ? "UNAVAILABLE (fail-fast)" : "UNAVAILABLE";
    }

    public static int bootstrapContractVersion() {
        ContractCheck check = checkContract();
        return check.status == ContractStatus.OK || check.status == ContractStatus.INCOMPATIBLE_VERSION ? check.version : -1;
    }

    public static String describeContractStatus() {
        ContractCheck check = checkContract();
        if (check.status == ContractStatus.OK) {
            return "OK (contract=" + check.version + ", " + check.visibility + ")";
        }
        if (check.status == ContractStatus.MISSING_CLASS) {
            return "UNAVAILABLE (missing AgentLifecycle)";
        }
        if (check.status == ContractStatus.MISSING_METHOD) {
            return "UNAVAILABLE (missing contractVersion)";
        }
        if (check.status == ContractStatus.BAD_RETURN_TYPE) {
            return "UNAVAILABLE (bad contractVersion return type: " + check.detail + ")";
        }
        if (check.status == ContractStatus.INVOCATION_FAILED) {
            return "UNAVAILABLE (contract check failed: " + check.detail + ")";
        }
        return "INCOMPATIBLE (expected " + MIN_CONTRACT_VERSION + ".." + MAX_CONTRACT_VERSION + ", found " + check.version + ")";
    }

    private static ContractCheck checkContract() {
        boolean strict = isStrictMode();
        ClassLoader loader = strict ? null : BootstrapBridge.class.getClassLoader();
        String visibility = strict ? "bootstrap-visible" : "classpath-visible";
        try {
            Class<?> lifecycle = Class.forName(AGENT_LIFECYCLE, false, loader);
            if (strict && lifecycle.getClassLoader() != null) {
                return ContractCheck.failed(ContractStatus.MISSING_CLASS, -1, "not bootstrap-visible");
            }
            java.lang.reflect.Method method = lifecycle.getMethod("contractVersion");
            if (method.getReturnType() != Integer.TYPE) {
                return ContractCheck.failed(ContractStatus.BAD_RETURN_TYPE, -1, method.getReturnType().getName());
            }
            Object result = method.invoke(null);
            if (!(result instanceof Number)) {
                return ContractCheck.failed(ContractStatus.BAD_RETURN_TYPE, -1, String.valueOf(result));
            }
            int version = ((Number) result).intValue();
            if (version < MIN_CONTRACT_VERSION || version > MAX_CONTRACT_VERSION) {
                return ContractCheck.failed(ContractStatus.INCOMPATIBLE_VERSION, version, null);
            }
            return ContractCheck.ok(version, visibility);
        } catch (ClassNotFoundException e) {
            return ContractCheck.failed(ContractStatus.MISSING_CLASS, -1, e.getMessage());
        } catch (NoSuchMethodException e) {
            return ContractCheck.failed(ContractStatus.MISSING_METHOD, -1, e.getMessage());
        } catch (Throwable t) {
            return ContractCheck.failed(ContractStatus.INVOCATION_FAILED, -1, t.getClass().getSimpleName());
        }
    }
}
