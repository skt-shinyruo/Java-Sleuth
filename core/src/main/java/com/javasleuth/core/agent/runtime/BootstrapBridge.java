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
    public static final String SPY_API = "com.javasleuth.bootstrap.spy.SleuthSpyAPI";
    public static final String TRACE_INTERCEPTOR = "com.javasleuth.bootstrap.monitor.TraceInterceptor";
    public static final String WATCH_INTERCEPTOR = "com.javasleuth.bootstrap.monitor.WatchInterceptor";
    public static final String MONITOR_INTERCEPTOR = "com.javasleuth.bootstrap.monitor.MonitorInterceptor";
    public static final String TT_INTERCEPTOR = "com.javasleuth.bootstrap.monitor.TtInterceptor";
    public static final String STACK_INTERCEPTOR = "com.javasleuth.bootstrap.monitor.StackInterceptor";
    public static final String VMTOOL_INTERCEPTOR = "com.javasleuth.bootstrap.monitor.VmToolInterceptor";

    private BootstrapBridge() {}

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
}
