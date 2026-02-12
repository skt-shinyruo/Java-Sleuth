package com.javasleuth.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
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

    private static final String CORE_JAR_ARG_KEY = "coreJar";
    private static final String CORE_JAR_SYS_PROP = "sleuth.agent.core.jar";
    private static final String CORE_JAR_ENV = "SLEUTH_AGENT_CORE_JAR";
    private static final String CORE_MARKER_ATTR = "Sleuth-Agent-Core";
    private static final String CORE_FILENAME_HINT = "java-sleuth-agent-core";
    private static final String CORE_FILENAME_SUFFIX = "-jar-with-dependencies.jar";

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
            applyAgentArgs(agentArgs);

            File selfJar = locateOwnJar();
            if (selfJar != null && selfJar.isFile() && inst != null) {
                try {
                    inst.appendToBootstrapClassLoaderSearch(new JarFile(selfJar));
                } catch (Throwable e) {
                    // Best-effort. Do not fail the target JVM if we cannot append.
                    System.err.println("Java-Sleuth: Failed to append bootstrap agent jar to bootstrap search: " + e.getMessage());
                }
            }

            File coreJar = locateCoreJar(agentArgs, selfJar);
            if (coreJar == null) {
                System.err.println("Java-Sleuth: Agent core jar not found.");
                System.err.println("  - Provide via agent args: coreJar=<path>");
                System.err.println("  - Or set -D" + CORE_JAR_SYS_PROP + "=<path> / env " + CORE_JAR_ENV);
                System.err.println("  - Or place core jar near agent jar: " + CORE_FILENAME_HINT + "*");
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

    private static void applyAgentArgs(String agentArgs) {
        if (agentArgs == null || agentArgs.trim().isEmpty()) {
            return;
        }
        String[] pairs = agentArgs.split(";");
        for (String pair : pairs) {
            if (pair == null) {
                continue;
            }
            String trimmed = pair.trim();
            if (trimmed.isEmpty() || !trimmed.contains("=")) {
                continue;
            }

            String[] kv = trimmed.split("=", 2);
            String key = kv[0].trim();
            String value = kv.length > 1 ? kv[1].trim() : "";
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }

            if ("configFile".equalsIgnoreCase(key)) {
                System.setProperty("sleuth.config.file", value);
            } else if (key.startsWith("sleuth.")) {
                System.setProperty(key, value);
            } else if (!CORE_JAR_ARG_KEY.equalsIgnoreCase(key)) {
                System.setProperty("sleuth." + key, value);
            }
        }
    }

    private static File locateCoreJar(String agentArgs, File selfJar) {
        String fromArgs = getArgValue(agentArgs, CORE_JAR_ARG_KEY);
        File f = fileIfExists(fromArgs);
        if (f != null) {
            return f;
        }

        f = fileIfExists(System.getProperty(CORE_JAR_SYS_PROP));
        if (f != null) {
            return f;
        }

        f = fileIfExists(System.getenv(CORE_JAR_ENV));
        if (f != null) {
            return f;
        }

        if (selfJar != null && selfJar.isFile()) {
            File dir = selfJar.getParentFile();
            File found = locateNewestCoreJarInDir(dir);
            if (found != null) {
                return found;
            }
        }

        // Fallback: scan current working directory (dev/IDE usage)
        return locateNewestCoreJarInDir(new File("."));
    }

    private static File locateNewestCoreJarInDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles((d, name) -> name != null &&
            name.contains(CORE_FILENAME_HINT) &&
            name.endsWith(CORE_FILENAME_SUFFIX));
        if (files == null || files.length == 0) {
            return null;
        }
        List<File> candidates = new ArrayList<>();
        for (File file : files) {
            if (file != null && file.isFile()) {
                candidates.add(file);
            }
        }
        candidates.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (File file : candidates) {
            if (isCoreJar(file)) {
                return file;
            }
        }
        return null;
    }

    private static boolean isCoreJar(File jar) {
        if (jar == null || !jar.isFile()) {
            return false;
        }
        try (JarFile jf = new JarFile(jar)) {
            java.util.jar.Manifest mf = jf.getManifest();
            if (mf == null) {
                return false;
            }
            java.util.jar.Attributes attrs = mf.getMainAttributes();
            if (attrs == null) {
                return false;
            }
            String marker = attrs.getValue(CORE_MARKER_ATTR);
            return marker != null && !marker.trim().isEmpty() && "true".equalsIgnoreCase(marker.trim());
        } catch (Exception e) {
            return false;
        }
    }

    private static String getArgValue(String agentArgs, String key) {
        if (agentArgs == null || key == null) {
            return null;
        }
        String[] pairs = agentArgs.split(";");
        for (String pair : pairs) {
            if (pair == null) {
                continue;
            }
            String trimmed = pair.trim();
            if (trimmed.isEmpty() || !trimmed.contains("=")) {
                continue;
            }
            String[] kv = trimmed.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String k = kv[0] != null ? kv[0].trim() : "";
            if (!key.equalsIgnoreCase(k)) {
                continue;
            }
            return kv[1] != null ? kv[1].trim() : null;
        }
        return null;
    }

    private static File fileIfExists(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File f = new File(path.trim());
        return f.isFile() ? f : null;
    }

    private static File locateOwnJar() {
        try {
            ProtectionDomain pd = SleuthAgent.class.getProtectionDomain();
            if (pd == null) {
                return null;
            }
            CodeSource cs = pd.getCodeSource();
            if (cs == null) {
                return null;
            }
            URL location = cs.getLocation();
            if (location == null) {
                return null;
            }
            File file;
            try {
                file = new File(location.toURI());
            } catch (Exception e) {
                file = new File(location.getPath());
            }
            if (file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
                return file;
            }
        } catch (Exception ignore) {
            // ignore
        }
        return null;
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
