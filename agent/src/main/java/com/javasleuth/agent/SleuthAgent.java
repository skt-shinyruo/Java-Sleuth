package com.javasleuth.agent;

import com.javasleuth.bootstrap.util.AgentArgsApplier;
import com.javasleuth.bootstrap.util.JarLocator;
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
        bootstrap(agentArgs, inst);
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        agentmain(agentArgs, inst);
    }

    private static void bootstrap(String agentArgs, Instrumentation inst) {
        URLClassLoader isolated = null;
        boolean registered = false;
        boolean registryAvailable = false;
        boolean legacyGateEntered = false;
        try {
            AgentArgsApplier.applyToSystemProperties(agentArgs);

            File selfJar = locateOwnJar();
            if (selfJar != null && selfJar.isFile() && inst != null) {
                try {
                    inst.appendToBootstrapClassLoaderSearch(new JarFile(selfJar));
                } catch (Throwable e) {
                    // Best-effort. Do not fail the target JVM if we cannot append.
                    System.err.println("Java-Sleuth: Failed to append bootstrap agent jar to bootstrap search: " + e.getMessage());
                }
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
