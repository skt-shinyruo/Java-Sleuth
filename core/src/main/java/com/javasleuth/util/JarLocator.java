package com.javasleuth.util;

import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Locate Java-Sleuth packaged artifacts at runtime.
 *
 * <p>Primary use case: find the agent jar to load via Attach API without hardcoding versions.</p>
 */
public final class JarLocator {
    public static final String AGENT_JAR_OVERRIDE_PROPERTY = "sleuth.agent.jar";
    public static final String AGENT_JAR_OVERRIDE_ENV = "SLEUTH_AGENT_JAR";
    public static final String DEFAULT_AGENT_JAR_SUFFIX = "-jar-with-dependencies.jar";

    private JarLocator() {}

    public static File locateAgentJar(Class<?> anchor) {
        File override = locateOverrideJar();
        if (override != null) {
            return override;
        }

        File fromClasspath = locateJarOnClasspath(DEFAULT_AGENT_JAR_SUFFIX);
        if (fromClasspath != null) {
            return fromClasspath;
        }

        File fromCodeSource = locateJarFromCodeSource(anchor);
        if (fromCodeSource != null) {
            return fromCodeSource;
        }

        // Fallback: scan common build directories under current working directory
        File fromCoreTarget = locateNewestJarBySuffix(new File("core/target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromCoreTarget != null) {
            return fromCoreTarget;
        }
        return locateNewestJarBySuffix(new File("target"), DEFAULT_AGENT_JAR_SUFFIX);
    }

    private static File locateOverrideJar() {
        String override = System.getProperty(AGENT_JAR_OVERRIDE_PROPERTY);
        if (override == null || override.trim().isEmpty()) {
            override = System.getenv(AGENT_JAR_OVERRIDE_ENV);
        }
        if (override == null || override.trim().isEmpty()) {
            return null;
        }
        File file = new File(override.trim());
        return file.isFile() ? file : null;
    }

    private static File locateJarOnClasspath(String suffix) {
        String cp = System.getProperty("java.class.path");
        if (cp == null || cp.trim().isEmpty()) {
            return null;
        }
        String[] parts = cp.split(java.util.regex.Pattern.quote(File.pathSeparator));
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (trimmed.isEmpty() || !trimmed.toLowerCase().endsWith(".jar")) {
                continue;
            }
            if (suffix != null && !suffix.isEmpty() && !trimmed.endsWith(suffix)) {
                continue;
            }
            File f = new File(trimmed);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    private static File locateJarFromCodeSource(Class<?> anchor) {
        if (anchor == null) {
            return null;
        }
        try {
            ProtectionDomain pd = anchor.getProtectionDomain();
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
            File file = toFile(location);
            if (file == null) {
                return null;
            }
            if (file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
                return file;
            }

            if (file.isDirectory()) {
                File cursor = file;
                for (int i = 0; i < 6 && cursor != null; i++) {
                    File foundHere = locateNewestJarBySuffix(cursor, DEFAULT_AGENT_JAR_SUFFIX);
                    if (foundHere != null) {
                        return foundHere;
                    }
                    cursor = cursor.getParentFile();
                }
            }
        } catch (Exception ignore) {
            // ignore
        }
        return null;
    }

    private static File toFile(URL url) {
        try {
            return new File(url.toURI());
        } catch (Exception e) {
            try {
                return new File(url.getPath());
            } catch (Exception ignore) {
                return null;
            }
        }
    }

    private static File locateNewestJarBySuffix(File dir, String suffix) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(suffix));
        if (files == null || files.length == 0) {
            return null;
        }
        List<File> candidates = new ArrayList<>();
        for (File f : files) {
            if (f != null && f.isFile()) {
                candidates.add(f);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingLong(File::lastModified).reversed());
        return candidates.get(0);
    }
}
