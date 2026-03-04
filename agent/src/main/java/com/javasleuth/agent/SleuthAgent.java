package com.javasleuth.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Base64;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Java-Sleuth bootstrap agent.
 *
 * <p>Design goals:
 * <ul>
 *   <li>Keep this jar thin and dependency-free (JDK only)</li>
 *   <li>Append bootstrap-visible spy/bridge classes (from a dedicated bridge jar) to BootstrapClassLoader</li>
 *   <li>Load the real implementation (container fat-jar) via isolated ClassLoader</li>
 * </ul>
 */
public final class SleuthAgent {
    private static final String CONTAINER_ENTRYPOINT_CLASS = "com.javasleuth.container.SleuthAgentContainerEntrypoint";
    private static final String BOOTSTRAP_AGENT_LIFECYCLE_CLASS = "com.javasleuth.bootstrap.agent.AgentLifecycle";

    private static final String BOOTSTRAP_JAR_LOCATOR_CLASS = "com.javasleuth.bootstrap.util.JarLocator";

    private static final String LOCATOR_DEBUG_PROPERTY = "sleuth.locator.debug";
    private static final String LOCATOR_ALLOW_CWD_SCAN_PROPERTY = "sleuth.locator.allowCwdScan";
    private static volatile boolean warnedCwdBridgeScan = false;

    private static final String BOOTSTRAP_BRIDGE_JAR_OVERRIDE_PROPERTY = "sleuth.agent.bootstrap.bridge.jar";
    private static final String BOOTSTRAP_BRIDGE_JAR_OVERRIDE_ENV = "SLEUTH_AGENT_BOOTSTRAP_BRIDGE_JAR";

    private static final String AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY = "sleuth.agent.container.jar";
    private static final String AGENT_CONTAINER_JAR_OVERRIDE_ENV = "SLEUTH_AGENT_CONTAINER_JAR";

    private SleuthAgent() {}

    public static void agentmain(String agentArgs, Instrumentation inst) {
        // Runtime attach: fail fast so the attach caller can detect startup errors.
        // This does NOT crash the target JVM; it only fails the attach request.
        bootstrap(agentArgs, inst, true);
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        // -javaagent mode: also fail-fast. Starting the JVM with a partially working agent is riskier than
        // failing fast, because enhancements may inject bootstrap calls that can crash application threads.
        bootstrap(agentArgs, inst, true);
    }

    private static void bootstrap(String agentArgs, Instrumentation inst, boolean failFast) {
        URLClassLoader isolated = null;
        long sessionId = 0L;
        boolean sessionBegun = false;
        try {
            File bridgeJar = locateBootstrapBridgeJar(agentArgs);
            if (bridgeJar != null && bridgeJar.isFile() && inst != null) {
                try {
                    if (jarContainsEntryPrefix(bridgeJar, "com/javasleuth/agent/")) {
                        throw new IllegalStateException(
                            "Java-Sleuth: Refusing to append non-minimal bridge jar to bootstrap (contains com.javasleuth.agent.*): "
                                + bridgeJar.getAbsolutePath()
                        );
                    }
                    inst.appendToBootstrapClassLoaderSearch(new JarFile(bridgeJar));
                } catch (Throwable e) {
                    // Best-effort append. If bootstrap bridge is unavailable, we must NOT continue to start the agent
                    // core, otherwise "watch/trace/..." enhancements may crash the target app at runtime.
                    System.err.println("Java-Sleuth: Failed to append bootstrap bridge jar to bootstrap search: " + e.getMessage());
                }
            }

            // Strong hint for operators: when bootstrap bridge is not actually visible, enhancer commands
            // must not be allowed to inject com.javasleuth.bootstrap.* calls into application bytecode.
            if (!isBootstrapBridgeAvailableBestEffort()) {
                System.err.println("Java-Sleuth: Bootstrap bridge is NOT available (bootstrap-visible classes missing).");
                if (failFast) {
                    System.err.println("  - Agent startup will fail-fast to protect the target JVM from NoClassDefFoundError/LinkageError.");
                }
                System.err.println("  - Fix: ensure appendToBootstrapClassLoaderSearch succeeds and bootstrap classes are loaded by BootstrapClassLoader.");
                if (failFast) {
                    throw new IllegalStateException("Java-Sleuth: Bootstrap bridge is not available; aborting agent startup");
                }
                return;
            }

            // SSOT: acquire lifecycle session before any global side effects (sysprops, classloader, transformers...).
            sessionId = bridgeTryBeginAttachOrZero();
            if (sessionId == 0L) {
                System.err.println("Java-Sleuth: Agent is already attached to this JVM (lifecycle gate)");
                return;
            }
            sessionBegun = true;

            if (!bridgeApplyAgentArgsIfAbsent(sessionId, agentArgs)) {
                System.err.println("Java-Sleuth: Failed to apply agent args (lifecycle gate); aborting agent startup.");
                bridgeFailBestEffort(sessionId, null);
                return;
            }

            File containerJar = locateAgentContainerJarBestEffort();
            if (containerJar == null) {
                // Backward compatible fallback: old builds might still have core fat-jar.
                containerJar = locateAgentCoreJarBestEffort();
            }
            if (containerJar == null) {
                System.err.println("Java-Sleuth: Agent container jar not found.");
                System.err.println("  - Provide via agent args: containerJar=<path>");
                System.err.println(
                    "  - Or set -D" + AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY + "=<path> / env " + AGENT_CONTAINER_JAR_OVERRIDE_ENV
                );
                System.err.println("  - Or place container jar near bootstrap jar: java-sleuth-container*");
                // 启动失败：回滚生命周期 session，允许修复参数后重试 attach。
                bridgeFailBestEffort(sessionId, null);
                return;
            }

            URL containerUrl = containerJar.toURI().toURL();
            isolated = new URLClassLoader(new URL[] { containerUrl }, null);

            // Bind this attach lifecycle boundary to the isolated ClassLoader (detach will close it).
            if (!bridgeCommitIsolatedClassLoader(sessionId, isolated)) {
                System.err.println("Java-Sleuth: Failed to commit isolated ClassLoader into lifecycle registry; aborting.");
                bridgeFailBestEffort(sessionId, isolated);
                closeQuietly(isolated);
                return;
            }

            Thread current = Thread.currentThread();
            ClassLoader old = current.getContextClassLoader();
            current.setContextClassLoader(isolated);
            try {
                Class<?> entry = Class.forName(CONTAINER_ENTRYPOINT_CLASS, true, isolated);
                java.lang.reflect.Method m = entry.getMethod("agentmain", String.class, Instrumentation.class);
                m.invoke(null, agentArgs, inst);
            } finally {
                current.setContextClassLoader(old);
            }
        } catch (Throwable e) {
            System.err.println("Java-Sleuth: Bootstrap agent failed: " + e.getMessage());
            if (Boolean.getBoolean("sleuth.agent.bootstrap.debug")) {
                e.printStackTrace(System.err);
            }
            try {
                if (sessionBegun) {
                    bridgeFailBestEffort(sessionId, isolated);
                }
            } catch (Throwable ignore) {
                // ignore
            }
            closeQuietly(isolated);
            if (failFast) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new RuntimeException(e);
            }
        }
    }

    private static File locateBootstrapBridgeJar(String agentArgs) {
        String override = System.getProperty(BOOTSTRAP_BRIDGE_JAR_OVERRIDE_PROPERTY);
        if (override == null || override.trim().isEmpty()) {
            override = System.getenv(BOOTSTRAP_BRIDGE_JAR_OVERRIDE_ENV);
        }
        if (override == null || override.trim().isEmpty()) {
            override = extractBridgeJarOverrideFromAgentArgs(agentArgs);
        }
        if (override != null && !override.trim().isEmpty()) {
            File file = new File(override.trim());
            debug("Java-Sleuth: bootstrap bridge override resolved to: " + file.getAbsolutePath());
            return file.isFile() ? file : null;
        }

        File agentJar = locateOwnJar();
        File agentDir = agentJar != null ? agentJar.getParentFile() : null;
        File candidate = null;

        // Preferred: bridge jar copied next to agent jar (agent/target or lib/).
        candidate = locateNewestJarByPrefix(agentDir, "java-sleuth-bootstrap-bridge-");
        if (candidate != null) {
            return candidate;
        }

        // Fallback: bootstrap jar itself (if present near agent jar).
        candidate = locateNewestJarByPrefix(agentDir, "java-sleuth-bootstrap-");
        if (candidate != null) {
            return candidate;
        }

        // Dev fallback: source checkout (agent/target -> bootstrap/target).
        File root = locateProjectRootFromAgentTarget(agentDir);
        if (root != null) {
            candidate = locateNewestJarByPrefix(new File(root, "bootstrap/target"), "java-sleuth-bootstrap-");
            if (candidate != null) {
                return candidate;
            }
            candidate = locateNewestJarByPrefix(new File(root, "lib"), "java-sleuth-bootstrap-");
            if (candidate != null) {
                return candidate;
            }
        }

        // Last resort: scan common relative directories from CWD (best-effort).
        if (!isCwdScanAllowed()) {
            debug("Java-Sleuth: CWD scan disabled by -D" + LOCATOR_ALLOW_CWD_SCAN_PROPERTY + "=false");
            return null;
        }
        candidate = locateNewestJarByPrefix(new File("lib"), "java-sleuth-bootstrap-");
        if (candidate != null) {
            warnCwdBridgeScanOnce();
            return candidate;
        }
        candidate = locateNewestJarByPrefix(new File("../lib"), "java-sleuth-bootstrap-");
        if (candidate != null) {
            warnCwdBridgeScanOnce();
            return candidate;
        }
        candidate = locateNewestJarByPrefix(new File("bootstrap/target"), "java-sleuth-bootstrap-");
        if (candidate != null) {
            warnCwdBridgeScanOnce();
        }
        return candidate;
    }

    private static void warnCwdBridgeScanOnce() {
        if (warnedCwdBridgeScan) {
            return;
        }
        warnedCwdBridgeScan = true;
        System.err.println(
            "Java-Sleuth: WARNING: bootstrap bridge jar resolved via CWD relative scan. " +
                "For deterministic startup, set -D" + BOOTSTRAP_BRIDGE_JAR_OVERRIDE_PROPERTY + "=<path> " +
                "(or env " + BOOTSTRAP_BRIDGE_JAR_OVERRIDE_ENV + "), or disable CWD scan via -D" +
                LOCATOR_ALLOW_CWD_SCAN_PROPERTY + "=false."
        );
    }

    private static boolean isCwdScanAllowed() {
        String v = System.getProperty(LOCATOR_ALLOW_CWD_SCAN_PROPERTY);
        if (v == null || v.trim().isEmpty()) {
            return true;
        }
        return !"false".equalsIgnoreCase(v.trim());
    }

    private static void debug(String msg) {
        if (msg == null || msg.trim().isEmpty()) {
            return;
        }
        if (Boolean.getBoolean("sleuth.agent.bootstrap.debug") || Boolean.getBoolean(LOCATOR_DEBUG_PROPERTY)) {
            System.err.println(msg);
        }
    }

    private static String extractBridgeJarOverrideFromAgentArgs(String agentArgs) {
        if (agentArgs == null) {
            return null;
        }
        String trimmedAll = agentArgs.trim();
        if (trimmedAll.isEmpty()) {
            return null;
        }
        String[] pairs = trimmedAll.split(";");
        for (String pair : pairs) {
            if (pair == null) {
                continue;
            }
            String trimmed = pair.trim();
            if (trimmed.isEmpty() || !trimmed.contains("=")) {
                continue;
            }
            String[] kv = trimmed.split("=", 2);
            String key = kv[0] != null ? kv[0].trim() : "";
            String value = kv.length > 1 && kv[1] != null ? kv[1].trim() : "";
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }

            if (BOOTSTRAP_BRIDGE_JAR_OVERRIDE_PROPERTY.equalsIgnoreCase(key)) {
                return value;
            }
            // Convenience keys (namespaced by AgentArgsApplier at later stages, but we need it before append).
            if ("bootstrapBridgeJar".equalsIgnoreCase(key) || "bridgeJar".equalsIgnoreCase(key)) {
                return value;
            }
            // Allow key without 'sleuth.' prefix (AgentArgsApplier will add it later).
            if ("agent.bootstrap.bridge.jar".equalsIgnoreCase(key)) {
                return value;
            }
        }
        return null;
    }

    private static File locateOwnJar() {
        try {
            java.security.ProtectionDomain pd = SleuthAgent.class.getProtectionDomain();
            if (pd == null) {
                return null;
            }
            java.security.CodeSource cs = pd.getCodeSource();
            if (cs == null) {
                return null;
            }
            URL location = cs.getLocation();
            if (location == null) {
                return null;
            }
            File file = new File(location.toURI());
            if (file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
                return file;
            }
        } catch (Exception ignore) {
            // ignore
        }
        return null;
    }

    private static File locateProjectRootFromAgentTarget(File agentDir) {
        if (agentDir == null) {
            return null;
        }
        // Pattern: <root>/agent/target/
        try {
            File agentModuleDir = agentDir.getParentFile(); // .../agent
            if (agentModuleDir == null) {
                return null;
            }
            if (!"agent".equalsIgnoreCase(agentModuleDir.getName())) {
                return null;
            }
            File root = agentModuleDir.getParentFile();
            return root != null && root.isDirectory() ? root : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static File locateNewestJarByPrefix(File dir, String prefix) {
        if (dir == null || !dir.isDirectory() || prefix == null || prefix.trim().isEmpty()) {
            return null;
        }
        File[] jars = dir.listFiles();
        if (jars == null || jars.length == 0) {
            return null;
        }
        File newestByMtime = null;
        long newestMtime = Long.MIN_VALUE;
        File bestByVersion = null;
        String bestVersion = null;
        long bestVersionMtime = Long.MIN_VALUE;
        boolean sawVersion = false;
        for (File f : jars) {
            if (f == null || !f.isFile()) {
                continue;
            }
            String name = f.getName();
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase();
            if (!lower.endsWith(".jar")) {
                continue;
            }
            if (lower.endsWith("-sources.jar") || lower.endsWith("-javadoc.jar") || lower.endsWith("-tests.jar")) {
                continue;
            }
            if (!name.startsWith(prefix)) {
                continue;
            }
            long mtime = f.lastModified();
            if (newestByMtime == null || mtime > newestMtime) {
                newestByMtime = f;
                newestMtime = mtime;
            }

            String version = extractVersionFromPrefixedJarName(name, prefix);
            if (version != null) {
                sawVersion = true;
                if (bestByVersion == null) {
                    bestByVersion = f;
                    bestVersion = version;
                    bestVersionMtime = mtime;
                    continue;
                }
                int cmp = compareVersionLike(version, bestVersion);
                if (cmp > 0 || (cmp == 0 && mtime > bestVersionMtime)) {
                    bestByVersion = f;
                    bestVersion = version;
                    bestVersionMtime = mtime;
                }
            }
        }
        File chosen = sawVersion ? bestByVersion : newestByMtime;
        if (chosen != null && sawVersion) {
            debug(
                "Java-Sleuth: Resolved jar by version preference: prefix=" + prefix +
                    ", version=" + bestVersion +
                    ", path=" + chosen.getAbsolutePath()
            );
        }
        return chosen;
    }

    private static String extractVersionFromPrefixedJarName(String name, String prefix) {
        if (name == null || prefix == null) {
            return null;
        }
        if (!name.startsWith(prefix) || !name.toLowerCase().endsWith(".jar")) {
            return null;
        }
        String tail = name.substring(prefix.length(), name.length() - ".jar".length());
        if (tail.isEmpty()) {
            return null;
        }
        char c0 = tail.charAt(0);
        // Typical Maven artifact: <prefix><version>.jar (version usually starts with a digit)
        return Character.isDigit(c0) ? tail : null;
    }

    private static boolean isVersionSeparator(char c) {
        return c == '.' || c == '-' || c == '_' || c == '+';
    }

    private static boolean remainderHasLetter(String s, int from) {
        if (s == null) {
            return false;
        }
        for (int i = Math.max(0, from); i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compare two version-like strings.
     *
     * <p>Rules (best-effort):
     * <ul>
     *   <li>Numeric runs are compared as numbers (so 1.10 > 1.2)</li>
     *   <li>Separators (., -, _, +) are skipped</li>
     *   <li>If one string ends earlier:
     *     <ul>
     *       <li>If the remainder contains letters (qualifier), the shorter one is treated as a stable release and wins</li>
     *       <li>Otherwise (only digits/separators remain), the longer one wins (more numeric segments)</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    private static int compareVersionLike(String a, String b) {
        if (a == null || a.trim().isEmpty()) {
            return (b == null || b.trim().isEmpty()) ? 0 : -1;
        }
        if (b == null || b.trim().isEmpty()) {
            return 1;
        }
        String sa = a.trim();
        String sb = b.trim();
        int ia = 0;
        int ib = 0;
        while (ia < sa.length() && ib < sb.length()) {
            char ca = sa.charAt(ia);
            char cb = sb.charAt(ib);

            if (isVersionSeparator(ca)) {
                ia++;
                continue;
            }
            if (isVersionSeparator(cb)) {
                ib++;
                continue;
            }

            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int za = ia;
                while (za < sa.length() && sa.charAt(za) == '0') {
                    za++;
                }
                int zb = ib;
                while (zb < sb.length() && sb.charAt(zb) == '0') {
                    zb++;
                }
                int ea = za;
                while (ea < sa.length() && Character.isDigit(sa.charAt(ea))) {
                    ea++;
                }
                int eb = zb;
                while (eb < sb.length() && Character.isDigit(sb.charAt(eb))) {
                    eb++;
                }
                int lenA = ea - za;
                int lenB = eb - zb;
                if (lenA != lenB) {
                    return lenA < lenB ? -1 : 1;
                }
                for (int k = 0; k < lenA; k++) {
                    char da = sa.charAt(za + k);
                    char db = sb.charAt(zb + k);
                    if (da != db) {
                        return da < db ? -1 : 1;
                    }
                }
                ia = ea;
                ib = eb;
                continue;
            }

            int la = Character.toLowerCase(ca);
            int lb = Character.toLowerCase(cb);
            if (la != lb) {
                return la < lb ? -1 : 1;
            }
            ia++;
            ib++;
        }

        if (ia == sa.length() && ib == sb.length()) {
            return 0;
        }
        if (ia == sa.length()) {
            // sa is a prefix of sb
            return remainderHasLetter(sb, ib) ? 1 : -1;
        }
        // sb is a prefix of sa
        return remainderHasLetter(sa, ia) ? -1 : 1;
    }

    private static boolean jarContainsEntryPrefix(File jarFile, String entryPrefix) {
        if (jarFile == null || !jarFile.isFile() || entryPrefix == null || entryPrefix.isEmpty()) {
            return false;
        }
        try (JarFile jf = new JarFile(jarFile)) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e == null) {
                    continue;
                }
                String name = e.getName();
                if (name != null && name.startsWith(entryPrefix)) {
                    return true;
                }
            }
        } catch (Exception ignore) {
            // ignore
        }
        return false;
    }

    private static long bridgeTryBeginAttachOrZero() {
        try {
            Class<?> c = Class.forName(BOOTSTRAP_AGENT_LIFECYCLE_CLASS, false, null);
            java.lang.reflect.Method m = c.getMethod("tryBeginAttach");
            Object r = m.invoke(null);
            return (r instanceof Number) ? ((Number) r).longValue() : 0L;
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    private static boolean bridgeApplyAgentArgsIfAbsent(long sessionId, String agentArgs) {
        try {
            Class<?> c = Class.forName(BOOTSTRAP_AGENT_LIFECYCLE_CLASS, false, null);
            java.lang.reflect.Method m = c.getMethod("applyAgentArgsIfAbsent", long.class, String.class);
            Object r = m.invoke(null, sessionId, agentArgs);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static boolean bridgeCommitIsolatedClassLoader(long sessionId, ClassLoader loader) {
        try {
            Class<?> c = Class.forName(BOOTSTRAP_AGENT_LIFECYCLE_CLASS, false, null);
            java.lang.reflect.Method m = c.getMethod("commitIsolatedClassLoader", long.class, ClassLoader.class);
            Object r = m.invoke(null, sessionId, loader);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static void bridgeFailBestEffort(long sessionId, ClassLoader loaderOrNull) {
        try {
            Class<?> c = Class.forName(BOOTSTRAP_AGENT_LIFECYCLE_CLASS, false, null);
            java.lang.reflect.Method m = c.getMethod("failBestEffort", long.class, ClassLoader.class);
            m.invoke(null, sessionId, loaderOrNull);
        } catch (Throwable ignore) {
            // ignore
        }
    }

    private static File locateAgentContainerJarBestEffort() {
        return invokeJarLocatorFileMethodBestEffort("locateAgentContainerJar");
    }

    private static File locateAgentCoreJarBestEffort() {
        return invokeJarLocatorFileMethodBestEffort("locateAgentCoreJar");
    }

    private static File invokeJarLocatorFileMethodBestEffort(String methodName) {
        if (methodName == null || methodName.trim().isEmpty()) {
            return null;
        }
        try {
            Class<?> c = Class.forName(BOOTSTRAP_JAR_LOCATOR_CLASS, false, null);
            java.lang.reflect.Method m = c.getMethod(methodName, Class.class);
            Object r = m.invoke(null, SleuthAgent.class);
            return (r instanceof File) ? (File) r : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean isBootstrapBridgeAvailableBestEffort() {
        // Lifecycle SSOT must be bootstrap-visible (used as attach gate and cleanup registry).
        if (!isBootstrapClassAvailable(BOOTSTRAP_AGENT_LIFECYCLE_CLASS)) {
            return false;
        }
        // Bootstrap-only utilities required by SleuthAgent bootstrap flow.
        if (!isBootstrapClassAvailable(BOOTSTRAP_JAR_LOCATOR_CLASS)) {
            return false;
        }
        // Interceptors are required for any bytecode enhancement that injects bootstrap calls.
        return isBootstrapClassAvailable("com.javasleuth.bootstrap.monitor.TraceInterceptor");
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

    private static void closeQuietly(URLClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            cl.close();
        } catch (Throwable ignore) {
            // ignore
        }
    }

    // Optional helper for manual debugging when running inside a JVM with no easy log sink.
    @SuppressWarnings("unused")
    private static String b64(String s) {
        if (s == null) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
