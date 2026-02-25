package com.javasleuth.agent;

import com.javasleuth.bootstrap.util.JarLocator;
import com.javasleuth.bootstrap.util.SystemPropertyRollbackRegistry;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Base64;
import java.util.jar.JarFile;

/**
 * Java-Sleuth bootstrap agent.
 *
 * <p>Design goals:
 * <ul>
 *   <li>Keep this jar thin and dependency-free (JDK only)</li>
 *   <li>Append bootstrap-visible spy/bridge classes (from this jar) to BootstrapClassLoader</li>
 *   <li>Load the real implementation (container fat-jar) via isolated ClassLoader</li>
 * </ul>
 */
public final class SleuthAgent {
    private static final String CONTAINER_ENTRYPOINT_CLASS = "com.javasleuth.container.SleuthAgentContainerEntrypoint";
    private static final String CORE_LOADER_REGISTRY_CLASS = "com.javasleuth.bootstrap.agent.CoreClassLoaderRegistry";

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
            File selfJar = locateOwnJar();
            if (selfJar != null && selfJar.isFile() && inst != null) {
                try {
                    inst.appendToBootstrapClassLoaderSearch(new JarFile(selfJar));
                } catch (Throwable e) {
                    // Best-effort append. If bootstrap bridge is unavailable, we must NOT continue to start the agent
                    // core, otherwise "watch/trace/..." enhancements may crash the target app at runtime.
                    System.err.println("Java-Sleuth: Failed to append bootstrap agent jar to bootstrap search: " + e.getMessage());
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
            SystemPropertyRollbackRegistry.applyAndRegisterIfAbsent(agentArgs);
            syspropsRegistered = true;

            File containerJar = JarLocator.locateAgentContainerJar(SleuthAgent.class);
            if (containerJar == null) {
                // Backward compatible fallback: old builds might still have core fat-jar.
                containerJar = JarLocator.locateAgentCoreJar(SleuthAgent.class);
            }
            if (containerJar == null) {
                System.err.println("Java-Sleuth: Agent container jar not found.");
                System.err.println("  - Provide via agent args: containerJar=<path>");
                System.err.println(
                    "  - Or set -D" + JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY + "=<path> / env " + JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_ENV
                );
                System.err.println("  - Or place container jar near bootstrap jar: java-sleuth-container*");
                // 启动失败：回滚闩锁，允许修复参数后重试 attach。
                if (legacyGateEntered) {
                    BootstrapAttachGate.releaseOnFailure();
                }
                if (syspropsRegistered) {
                    SystemPropertyRollbackRegistry.rollbackAndClearBestEffort();
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
                SystemPropertyRollbackRegistry.rollbackAndClearBestEffort();
            }
            if (failFast) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new RuntimeException(e);
            }
        }
    }

    private static File locateOwnJar() {
        return JarLocator.locateCodeSourceJar(SleuthAgent.class);
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
