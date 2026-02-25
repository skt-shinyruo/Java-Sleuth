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
    private static final String CORE_LOADER_REGISTRY_CLASS = "com.javasleuth.bootstrap.agent.CoreClassLoaderRegistry";

    private static final String BOOTSTRAP_JAR_LOCATOR_CLASS = "com.javasleuth.bootstrap.util.JarLocator";
    private static final String BOOTSTRAP_SYSPROP_ROLLBACK_REGISTRY_CLASS =
        "com.javasleuth.bootstrap.util.SystemPropertyRollbackRegistry";

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
        boolean registered = false;
        boolean registryAvailable = false;
        boolean legacyGateEntered = false;
        boolean syspropsRegistered = false;
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

            // Prefer the bootstrap-visible ClassLoader registry as SSOT attach gate.
            // If registry indicates "already attached", rollback local gate and return early.
            Boolean alreadyAttached = bridgeIsRegisteredOrNull();
            registryAvailable = alreadyAttached != null;
            if (Boolean.TRUE.equals(alreadyAttached)) {
                System.err.println("Java-Sleuth: Agent is already attached to this JVM (registry gate)");
                return;
            }

            if (!registryAvailable) {
                // Fallback: if registry bridge is unavailable, use legacy CAS gate to avoid duplicate attach.
                if (!BootstrapAttachGate.tryEnter()) {
                    return;
                }
                legacyGateEntered = true;
            }

            // Apply agentArgs only after passing attach gates, to avoid polluting sysprop state on "already attached" paths.
            syspropsRegistered = applyAndRegisterAgentArgsBestEffort(agentArgs);

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
                // 启动失败：回滚闩锁，允许修复参数后重试 attach。
                if (legacyGateEntered) {
                    BootstrapAttachGate.releaseOnFailure();
                }
                if (syspropsRegistered) {
                    rollbackAgentArgsBestEffort();
                }
                return;
            }

            URL containerUrl = containerJar.toURI().toURL();
            isolated = new URLClassLoader(new URL[] { containerUrl }, null);

            // Register core/container ClassLoader into bootstrap-visible registry.
            // This becomes the lifecycle boundary for detach → re-attach and JAR handle release.
            if (registryAvailable) {
                Boolean ok = bridgeTryRegisterOrNull(isolated);
                if (ok == null) {
                    // Degrade gracefully: fall back to legacy gate and continue without registry.
                    registryAvailable = false;
                    if (!BootstrapAttachGate.tryEnter()) {
                        closeQuietly(isolated);
                        return;
                    }
                    legacyGateEntered = true;
                } else if (!ok) {
                    System.err.println("Java-Sleuth: Agent is already attached to this JVM (registry CAS)");
                    closeQuietly(isolated);
                    return;
                } else {
                    registered = true;
                }
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
            // 启动失败：回滚闩锁 + 回滚 registry，允许重试 attach。
            try {
                if (registryAvailable && registered) {
                    bridgeReleaseOnFailure(isolated);
                }
            } catch (Throwable ignore) {
                // ignore
            }
            closeQuietly(isolated);
            if (legacyGateEntered) {
                BootstrapAttachGate.releaseOnFailure();
            }
            if (syspropsRegistered) {
                rollbackAgentArgsBestEffort();
            }
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
        candidate = locateNewestJarByPrefix(new File("lib"), "java-sleuth-bootstrap-");
        if (candidate != null) {
            return candidate;
        }
        candidate = locateNewestJarByPrefix(new File("../lib"), "java-sleuth-bootstrap-");
        if (candidate != null) {
            return candidate;
        }
        return locateNewestJarByPrefix(new File("bootstrap/target"), "java-sleuth-bootstrap-");
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
        File newest = null;
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
            if (newest == null || f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        return newest;
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

    private static boolean applyAndRegisterAgentArgsBestEffort(String agentArgs) {
        try {
            Class<?> c = Class.forName(BOOTSTRAP_SYSPROP_ROLLBACK_REGISTRY_CLASS, false, null);
            java.lang.reflect.Method m = c.getMethod("applyAndRegisterIfAbsent", String.class);
            m.invoke(null, agentArgs);
            return true;
        } catch (Throwable ignore) {
            // ignore
            return false;
        }
    }

    private static void rollbackAgentArgsBestEffort() {
        try {
            Class<?> c = Class.forName(BOOTSTRAP_SYSPROP_ROLLBACK_REGISTRY_CLASS, false, null);
            java.lang.reflect.Method m = c.getMethod("rollbackAndClearBestEffort");
            m.invoke(null);
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

    private static Boolean bridgeIsRegisteredOrNull() {
        try {
            Class<?> c = Class.forName(CORE_LOADER_REGISTRY_CLASS, false, null);
            java.lang.reflect.Method m = c.getMethod("isRegistered");
            Object r = m.invoke(null);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Boolean bridgeTryRegisterOrNull(ClassLoader loader) {
        try {
            Class<?> c = Class.forName(CORE_LOADER_REGISTRY_CLASS, false, null);
            java.lang.reflect.Method m = c.getMethod("tryRegister", ClassLoader.class);
            Object r = m.invoke(null, loader);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static void bridgeReleaseOnFailure(ClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            Class<?> c = Class.forName(CORE_LOADER_REGISTRY_CLASS, false, null);
            java.lang.reflect.Method m = c.getMethod("releaseOnFailure", ClassLoader.class);
            m.invoke(null, loader);
        } catch (Throwable ignore) {
            // ignore
        }
    }

    private static boolean isBootstrapBridgeAvailableBestEffort() {
        // Registry gate is one required bootstrap-visible bridge.
        if (!isBootstrapClassAvailable(CORE_LOADER_REGISTRY_CLASS)) {
            return false;
        }
        // Bootstrap-only utilities required by SleuthAgent bootstrap flow.
        if (!isBootstrapClassAvailable(BOOTSTRAP_JAR_LOCATOR_CLASS)) {
            return false;
        }
        if (!isBootstrapClassAvailable(BOOTSTRAP_SYSPROP_ROLLBACK_REGISTRY_CLASS)) {
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
