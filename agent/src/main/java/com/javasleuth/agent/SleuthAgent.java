package com.javasleuth.agent;

import com.javasleuth.util.AgentArgsApplier;
import com.javasleuth.util.JarLocator;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

/**
 * Java-Sleuth bootstrap agent.
 *
 * <p>Design goals:
 * <ul>
 *   <li>Keep this jar thin and dependency-free (JDK only)</li>
 *   <li>Append bootstrap-visible spy/bridge classes (from this jar) to BootstrapClassLoader</li>
 *   <li>Load the real implementation (agent-core fat-jar) via isolated ClassLoader</li>
 * </ul>
 */
public final class SleuthAgent {
    private static final AtomicBoolean ATTACHED = new AtomicBoolean(false);

    private static final String CORE_ENTRYPOINT_CLASS = "com.javasleuth.agent.core.SleuthAgentCore";

    private SleuthAgent() {}

    public static void agentmain(String agentArgs, Instrumentation inst) {
        if (!ATTACHED.compareAndSet(false, true)) {
            return;
        }
        bootstrap(agentArgs, inst);
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        agentmain(agentArgs, inst);
    }

    private static void bootstrap(String agentArgs, Instrumentation inst) {
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

            File coreJar = JarLocator.locateAgentCoreJar(SleuthAgent.class);
            if (coreJar == null) {
                System.err.println("Java-Sleuth: Agent core jar not found.");
                System.err.println("  - Provide via agent args: coreJar=<path>");
                System.err.println("  - Or set -D" + JarLocator.AGENT_CORE_JAR_OVERRIDE_PROPERTY + "=<path> / env " + JarLocator.AGENT_CORE_JAR_OVERRIDE_ENV);
                System.err.println("  - Or place core jar near bootstrap jar: java-sleuth-agent-core*");
                return;
            }

            URL coreUrl = coreJar.toURI().toURL();
            URLClassLoader isolated = new URLClassLoader(new URL[]{coreUrl}, null);
            Thread current = Thread.currentThread();
            ClassLoader old = current.getContextClassLoader();
            current.setContextClassLoader(isolated);
            try {
                Class<?> entry = Class.forName(CORE_ENTRYPOINT_CLASS, true, isolated);
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
        }
    }

    private static File locateOwnJar() {
        return JarLocator.locateCodeSourceJar(SleuthAgent.class);
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
