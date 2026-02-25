package com.javasleuth.bootstrap.util;

import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
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
    public static final String AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY = "sleuth.agent.container.jar";
    public static final String AGENT_CONTAINER_JAR_OVERRIDE_ENV = "SLEUTH_AGENT_CONTAINER_JAR";

    public static final String LOCATOR_DEBUG_PROPERTY = "sleuth.locator.debug";
    public static final String LOCATOR_ALLOW_CWD_SCAN_PROPERTY = "sleuth.locator.allowCwdScan";

    public static final String DEFAULT_AGENT_JAR_SUFFIX = "-jar-with-dependencies.jar";
    public static final String MANIFEST_MARKER_BOOTSTRAP = "Sleuth-Agent-Bootstrap";
    public static final String MANIFEST_MARKER_CORE = "Sleuth-Agent-Core";
    public static final String MANIFEST_MARKER_CONTAINER = "Sleuth-Agent-Container";

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
        if (!isCwdScanAllowed()) {
            debug("JarLocator: CWD scan disabled by -D" + LOCATOR_ALLOW_CWD_SCAN_PROPERTY + "=false");
            return null;
        }
        File fromAgentTarget = locateNewestAgentJarBySuffix(cwd("agent/target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromAgentTarget != null) {
            return fromAgentTarget;
        }
        File fromCoreTarget = locateNewestAgentJarBySuffix(cwd("core/target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromCoreTarget != null) {
            return fromCoreTarget;
        }
        File fromTarget = locateNewestAgentJarBySuffix(cwd("target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromTarget != null) {
            return fromTarget;
        }
        File fromLib = locateNewestAgentJarBySuffix(cwd("lib"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromLib != null) {
            return fromLib;
        }
        return locateNewestAgentJarBySuffix(cwd("../lib"), DEFAULT_AGENT_JAR_SUFFIX);
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
        if (!isCwdScanAllowed()) {
            debug("JarLocator: CWD scan disabled by -D" + LOCATOR_ALLOW_CWD_SCAN_PROPERTY + "=false");
            return null;
        }
        File fromCoreTarget = locateNewestCoreJarBySuffix(cwd("core/target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromCoreTarget != null) {
            return fromCoreTarget;
        }
        File fromTarget = locateNewestCoreJarBySuffix(cwd("target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromTarget != null) {
            return fromTarget;
        }
        File fromLib = locateNewestCoreJarBySuffix(cwd("lib"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromLib != null) {
            return fromLib;
        }
        return locateNewestCoreJarBySuffix(cwd("../lib"), DEFAULT_AGENT_JAR_SUFFIX);
    }

    public static File locateAgentContainerJar(Class<?> anchor) {
        File override = locateContainerOverrideJar();
        if (override != null) {
            return override;
        }

        File fromClasspath = locateContainerJarOnClasspath(DEFAULT_AGENT_JAR_SUFFIX);
        if (fromClasspath != null) {
            return fromClasspath;
        }

        File fromCodeSource = locateContainerJarFromCodeSource(anchor);
        if (fromCodeSource != null) {
            return fromCodeSource;
        }

        // Fallback: scan common build/release directories under current working directory
        if (!isCwdScanAllowed()) {
            debug("JarLocator: CWD scan disabled by -D" + LOCATOR_ALLOW_CWD_SCAN_PROPERTY + "=false");
            return null;
        }
        File fromContainerTarget = locateNewestContainerJarBySuffix(cwd("container/target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromContainerTarget != null) {
            return fromContainerTarget;
        }
        File fromTarget = locateNewestContainerJarBySuffix(cwd("target"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromTarget != null) {
            return fromTarget;
        }
        File fromLib = locateNewestContainerJarBySuffix(cwd("lib"), DEFAULT_AGENT_JAR_SUFFIX);
        if (fromLib != null) {
            return fromLib;
        }
        return locateNewestContainerJarBySuffix(cwd("../lib"), DEFAULT_AGENT_JAR_SUFFIX);
    }

    private static File cwd(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return null;
        }
        String userDir = System.getProperty("user.dir");
        if (userDir == null || userDir.trim().isEmpty()) {
            return new File(relativePath);
        }
        return new File(new File(userDir), relativePath);
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
        if (Boolean.getBoolean(LOCATOR_DEBUG_PROPERTY)) {
            System.err.println(msg);
        }
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

    private static File locateContainerOverrideJar() {
        String override = System.getProperty(AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY);
        if (override == null || override.trim().isEmpty()) {
            override = System.getenv(AGENT_CONTAINER_JAR_OVERRIDE_ENV);
        }
        if (override == null || override.trim().isEmpty()) {
            return null;
        }
        File file = new File(override.trim());
        if (!file.isFile()) {
            return null;
        }
        return isContainerJar(file) ? file : null;
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

    private static File locateContainerJarOnClasspath(String suffix) {
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
            if (f.isFile() && isContainerJar(f)) {
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

    private static File locateContainerJarFromCodeSource(Class<?> anchor) {
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
                if (isContainerJar(file)) {
                    return file;
                }
                return locateNewestContainerJarBySuffix(file.getParentFile(), DEFAULT_AGENT_JAR_SUFFIX);
            }

            if (file.isDirectory()) {
                File cursor = file;
                for (int i = 0; i < 6 && cursor != null; i++) {
                    File foundHere = locateNewestContainerJarBySuffix(cursor, DEFAULT_AGENT_JAR_SUFFIX);
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
        sortCandidatesPreferVersion(candidates, suffix);
        for (File f : candidates) {
            if (f != null && f.isFile() && isBootstrapAgentJar(f)) {
                debug("JarLocator: resolved bootstrap agent jar: " + f.getAbsolutePath());
                return f;
            }
        }
        for (File f : candidates) {
            if (f != null && f.isFile() && isAgentJar(f)) {
                debug("JarLocator: resolved agent jar: " + f.getAbsolutePath());
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
        sortCandidatesPreferVersion(candidates, suffix);
        for (File f : candidates) {
            if (f != null && f.isFile() && isCoreJar(f)) {
                debug("JarLocator: resolved core jar: " + f.getAbsolutePath());
                return f;
            }
        }
        return null;
    }

    private static File locateNewestContainerJarBySuffix(File dir, String suffix) {
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
        sortCandidatesPreferVersion(candidates, suffix);
        for (File f : candidates) {
            if (f != null && f.isFile() && isContainerJar(f)) {
                debug("JarLocator: resolved container jar: " + f.getAbsolutePath());
                return f;
            }
        }
        return null;
    }

    private static void sortCandidatesPreferVersion(List<File> candidates, String suffix) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        candidates.sort((a, b) -> {
            if (a == b) {
                return 0;
            }
            String na = a != null ? a.getName() : null;
            String nb = b != null ? b.getName() : null;
            String va = extractVersionFromSuffixedJarName(na, suffix);
            String vb = extractVersionFromSuffixedJarName(nb, suffix);
            if (va != null && vb != null) {
                int cmp = compareVersionLike(va, vb);
                if (cmp != 0) {
                    return -cmp; // desc
                }
            } else if (va != null) {
                return -1;
            } else if (vb != null) {
                return 1;
            }

            long ma = a != null ? a.lastModified() : 0L;
            long mb = b != null ? b.lastModified() : 0L;
            if (ma != mb) {
                return ma < mb ? 1 : -1; // desc
            }
            String sa = na != null ? na : "";
            String sb = nb != null ? nb : "";
            return sa.compareTo(sb);
        });
    }

    private static String extractVersionFromSuffixedJarName(String name, String suffix) {
        if (name == null || suffix == null || suffix.isEmpty()) {
            return null;
        }
        if (!name.endsWith(suffix)) {
            return null;
        }
        String base = name.substring(0, name.length() - suffix.length());
        if (base.isEmpty()) {
            return null;
        }
        for (int i = base.length() - 1; i >= 0; i--) {
            if (base.charAt(i) != '-') {
                continue;
            }
            String token = base.substring(i + 1);
            if (token.isEmpty()) {
                continue;
            }
            if (Character.isDigit(token.charAt(0))) {
                return token;
            }
        }
        return null;
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
            return remainderHasLetter(sb, ib) ? 1 : -1;
        }
        return remainderHasLetter(sa, ia) ? -1 : 1;
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

    private static boolean isContainerJar(File jarFile) {
        if (jarFile == null || !jarFile.isFile()) {
            return false;
        }
        try (JarFile jf = new JarFile(jarFile)) {
            Manifest mf = jf.getManifest();
            return hasContainerManifestAttributes(mf);
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

    private static boolean hasContainerManifestAttributes(Manifest mf) {
        if (mf == null) {
            return false;
        }
        Attributes attrs = mf.getMainAttributes();
        if (attrs == null) {
            return false;
        }
        String marker = attrs.getValue(MANIFEST_MARKER_CONTAINER);
        return marker != null && "true".equalsIgnoreCase(marker.trim());
    }
}
