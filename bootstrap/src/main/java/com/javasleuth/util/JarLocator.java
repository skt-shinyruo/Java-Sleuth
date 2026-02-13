package com.javasleuth.util;

import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Locate Java-Sleuth packaged artifacts at runtime.
 *
 * <p>Primary use case: find the agent jar to load via Attach API without hardcoding versions.</p>
 */
public final class JarLocator {
    public static final String AGENT_JAR_OVERRIDE_PROPERTY = "sleuth.agent.jar";
    public static final String AGENT_JAR_OVERRIDE_ENV = "SLEUTH_AGENT_JAR";
    public static final String AGENT_CORE_JAR_OVERRIDE_PROPERTY = "sleuth.agent.core.jar";
    public static final String AGENT_CORE_JAR_OVERRIDE_ENV = "SLEUTH_AGENT_CORE_JAR";

    public static final String DEFAULT_AGENT_JAR_SUFFIX = "-jar-with-dependencies.jar";
    public static final String MANIFEST_MARKER_BOOTSTRAP = "Sleuth-Agent-Bootstrap";
    public static final String MANIFEST_MARKER_CORE = "Sleuth-Agent-Core";

    private JarLocator() {}

    /**
     * Locate the jar file that contains the given anchor class (via ProtectionDomain/CodeSource).
     *
     * <p>This is a generic helper and does not validate manifest markers.</p>
     */
    public static File locateCodeSourceJar(Class<?> anchor) {
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
            if (file != null && file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
                return file;
            }
        } catch (Exception ignore) {
            // ignore
        }
        return null;
    }

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
        File fromAgentTarget = locateNewestAgentJarBySuffix(new File("agent/target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromAgentTarget != null) {
            return fromAgentTarget;
        }
        File fromCoreTarget = locateNewestAgentJarBySuffix(new File("core/target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromCoreTarget != null) {
            return fromCoreTarget;
        }
        File fromTarget = locateNewestAgentJarBySuffix(new File("target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromTarget != null) {
            return fromTarget;
        }
        File fromLib = locateNewestAgentJarBySuffix(new File("lib"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromLib != null) {
            return fromLib;
        }
        return locateNewestAgentJarBySuffix(new File("../lib"), DEFAULT_AGENT_JAR_SUFFIX);
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
        if (!file.isFile()) {
            return null;
        }
        return isAgentJar(file) ? file : null;
    }

    public static File locateAgentCoreJar(Class<?> anchor) {
        File override = locateCoreOverrideJar();
        if (override != null) {
            return override;
        }

        File fromClasspath = locateCoreJarOnClasspath(DEFAULT_AGENT_JAR_SUFFIX);
        if (fromClasspath != null) {
            return fromClasspath;
        }

        File fromCodeSource = locateCoreJarFromCodeSource(anchor);
        if (fromCodeSource != null) {
            return fromCodeSource;
        }

        // Fallback: scan common build/release directories under current working directory
        File fromCoreTarget = locateNewestCoreJarBySuffix(new File("core/target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromCoreTarget != null) {
            return fromCoreTarget;
        }
        File fromTarget = locateNewestCoreJarBySuffix(new File("target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromTarget != null) {
            return fromTarget;
        }
        File fromLib = locateNewestCoreJarBySuffix(new File("lib"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromLib != null) {
            return fromLib;
        }
        return locateNewestCoreJarBySuffix(new File("../lib"), DEFAULT_AGENT_JAR_SUFFIX);
    }

    private static File locateCoreOverrideJar() {
        String override = System.getProperty(AGENT_CORE_JAR_OVERRIDE_PROPERTY);
        if (override == null || override.trim().isEmpty()) {
            override = System.getenv(AGENT_CORE_JAR_OVERRIDE_ENV);
        }
        if (override == null || override.trim().isEmpty()) {
            return null;
        }
        File file = new File(override.trim());
        if (!file.isFile()) {
            return null;
        }
        return isCoreJar(file) ? file : null;
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
            if (f.isFile() && isBootstrapAgentJar(f)) {
                return f;
            }
        }
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
            if (f.isFile() && isAgentJar(f)) {
                return f;
            }
        }
        return null;
    }

    private static File locateCoreJarOnClasspath(String suffix) {
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
            if (f.isFile() && isCoreJar(f)) {
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
                if (isAgentJar(file)) {
                    return file;
                }
                // If the anchor is the launcher jar, prefer an agent jar from the same directory.
                File sibling = locateNewestAgentJarBySuffix(file.getParentFile(), DEFAULT_AGENT_JAR_SUFFIX);
                return sibling;
            }

            if (file.isDirectory()) {
                File cursor = file;
                for (int i = 0; i < 6 && cursor != null; i++) {
                    File foundHere = locateNewestAgentJarBySuffix(cursor, DEFAULT_AGENT_JAR_SUFFIX);
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

    private static File locateCoreJarFromCodeSource(Class<?> anchor) {
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
                if (isCoreJar(file)) {
                    return file;
                }
                return locateNewestCoreJarBySuffix(file.getParentFile(), DEFAULT_AGENT_JAR_SUFFIX);
            }

            if (file.isDirectory()) {
                File cursor = file;
                for (int i = 0; i < 6 && cursor != null; i++) {
                    File foundHere = locateNewestCoreJarBySuffix(cursor, DEFAULT_AGENT_JAR_SUFFIX);
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

    private static File locateNewestAgentJarBySuffix(File dir, String suffix) {
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
        for (File f : candidates) {
            if (f != null && f.isFile() && isBootstrapAgentJar(f)) {
                return f;
            }
        }
        for (File f : candidates) {
            if (f != null && f.isFile() && isAgentJar(f)) {
                return f;
            }
        }
        return null;
    }

    private static File locateNewestCoreJarBySuffix(File dir, String suffix) {
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
        for (File f : candidates) {
            if (f != null && f.isFile() && isCoreJar(f)) {
                return f;
            }
        }
        return null;
    }

    private static boolean isAgentJar(File jarFile) {
        if (jarFile == null || !jarFile.isFile()) {
            return false;
        }
        try (JarFile jf = new JarFile(jarFile)) {
            Manifest mf = jf.getManifest();
            return hasAgentManifestAttributes(mf);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isBootstrapAgentJar(File jarFile) {
        if (!isAgentJar(jarFile)) {
            return false;
        }
        try (JarFile jf = new JarFile(jarFile)) {
            Manifest mf = jf.getManifest();
            Attributes attrs = mf != null ? mf.getMainAttributes() : null;
            if (attrs == null) {
                return false;
            }
            String marker = attrs.getValue(MANIFEST_MARKER_BOOTSTRAP);
            return marker != null && "true".equalsIgnoreCase(marker.trim());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isCoreJar(File jarFile) {
        if (jarFile == null || !jarFile.isFile()) {
            return false;
        }
        try (JarFile jf = new JarFile(jarFile)) {
            Manifest mf = jf.getManifest();
            return hasCoreManifestAttributes(mf);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasAgentManifestAttributes(Manifest mf) {
        if (mf == null) {
            return false;
        }
        Attributes attrs = mf.getMainAttributes();
        if (attrs == null) {
            return false;
        }
        String agentClass = attrs.getValue("Agent-Class");
        String premainClass = attrs.getValue("Premain-Class");
        return (agentClass != null && !agentClass.trim().isEmpty()) ||
               (premainClass != null && !premainClass.trim().isEmpty());
    }

    private static boolean hasCoreManifestAttributes(Manifest mf) {
        if (mf == null) {
            return false;
        }
        Attributes attrs = mf.getMainAttributes();
        if (attrs == null) {
            return false;
        }
        String marker = attrs.getValue(MANIFEST_MARKER_CORE);
        return marker != null && "true".equalsIgnoreCase(marker.trim());
    }
}
