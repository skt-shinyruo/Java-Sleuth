package com.javasleuth.bootstrap.util;

import org.junit.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.Assert.*;

public class JarLocatorTest {

    @Test
    public void testLocateAgentJarByOverrideProperty() throws Exception {
        String old = System.getProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
        File tmp = File.createTempFile("sleuth-agent-", ".jar");
        tmp.deleteOnExit();

        try {
            Map<String, String> mf = new HashMap<>();
            mf.put("Agent-Class", "com.javasleuth.agent.SleuthAgent");
            mf.put("Premain-Class", "com.javasleuth.agent.SleuthAgent");
            writeJarWithManifest(tmp, mf);
            System.setProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY, tmp.getAbsolutePath());
            File located = JarLocator.locateAgentJar(JarLocatorTest.class);
            assertNotNull(located);
            assertEquals(tmp.getAbsolutePath(), located.getAbsolutePath());
        } finally {
            if (old == null) {
                System.clearProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
            } else {
                System.setProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY, old);
            }
        }
    }

    @Test
    public void testLocateAgentContainerJarByOverrideProperty() throws Exception {
        String old = System.getProperty(JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY);
        File tmp = File.createTempFile("sleuth-agent-container-", ".jar");
        tmp.deleteOnExit();

        try {
            Map<String, String> mf = new HashMap<>();
            mf.put(JarLocator.MANIFEST_MARKER_CONTAINER, "true");
            writeJarWithManifest(tmp, mf);

            System.setProperty(JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY, tmp.getAbsolutePath());
            File located = JarLocator.locateAgentContainerJar(JarLocatorTest.class);
            assertNotNull(located);
            assertEquals(tmp.getAbsolutePath(), located.getAbsolutePath());
        } finally {
            if (old == null) {
                System.clearProperty(JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY);
            } else {
                System.setProperty(JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY, old);
            }
        }
    }

    @Test
    public void testLocateAgentJarByClasspathPrefersAgentManifest() throws Exception {
        String oldOverride = System.getProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
        String oldCp = System.getProperty("java.class.path");

        File launcherJar = File.createTempFile("sleuth-launcher-", JarLocator.DEFAULT_AGENT_JAR_SUFFIX);
        File agentJar = File.createTempFile("sleuth-agent-", JarLocator.DEFAULT_AGENT_JAR_SUFFIX);
        launcherJar.deleteOnExit();
        agentJar.deleteOnExit();

        Map<String, String> launcherMf = new HashMap<>();
        launcherMf.put("Main-Class", "com.javasleuth.launcher.SleuthLauncher");
        writeJarWithManifest(launcherJar, launcherMf);

        Map<String, String> agentMf = new HashMap<>();
        agentMf.put("Agent-Class", "com.javasleuth.agent.SleuthAgent");
        agentMf.put("Premain-Class", "com.javasleuth.agent.SleuthAgent");
        writeJarWithManifest(agentJar, agentMf);

        try {
            System.clearProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
            System.setProperty(
                "java.class.path",
                launcherJar.getAbsolutePath() + File.pathSeparator + agentJar.getAbsolutePath()
            );
            File located = JarLocator.locateAgentJar(JarLocatorTest.class);
            assertNotNull(located);
            assertEquals(agentJar.getAbsolutePath(), located.getAbsolutePath());
        } finally {
            if (oldOverride == null) {
                System.clearProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
            } else {
                System.setProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY, oldOverride);
            }
            if (oldCp == null) {
                System.clearProperty("java.class.path");
            } else {
                System.setProperty("java.class.path", oldCp);
            }
        }
    }

    @Test
    public void testLocateAgentJarByCwdScanPrefersHigherVersionOverLastModified() throws Exception {
        String oldOverride = System.getProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
        String oldCp = System.getProperty("java.class.path");
        String oldUserDir = System.getProperty("user.dir");

        File root = createTempDir("sleuth-locator-root-");
        File agentTarget = new File(root, "agent/target");
        assertTrue(agentTarget.mkdirs());

        // Two candidates: ensure "1.10.0" wins over "1.9.0" even if lastModified differs.
        File v190 = new File(agentTarget, "java-sleuth-agent-1.9.0" + JarLocator.DEFAULT_AGENT_JAR_SUFFIX);
        File v1100 = new File(agentTarget, "java-sleuth-agent-1.10.0" + JarLocator.DEFAULT_AGENT_JAR_SUFFIX);

        Map<String, String> mf = new HashMap<>();
        mf.put("Agent-Class", "com.javasleuth.agent.SleuthAgent");
        mf.put("Premain-Class", "com.javasleuth.agent.SleuthAgent");
        writeJarWithManifest(v190, mf);
        writeJarWithManifest(v1100, mf);

        // Make the lower version look "newer" by mtime to prove we are not relying on lastModified.
        assertTrue(v190.setLastModified(System.currentTimeMillis() + 10_000));

        try {
            System.clearProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
            System.setProperty("java.class.path", "");
            System.setProperty("user.dir", root.getAbsolutePath());

            File located = JarLocator.locateAgentJar(null);
            assertNotNull(located);
            assertEquals(v1100.getAbsolutePath(), located.getAbsolutePath());
        } finally {
            restoreSysprop(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY, oldOverride);
            restoreSysprop("java.class.path", oldCp);
            restoreSysprop("user.dir", oldUserDir);
        }
    }

    @Test
    public void testLocateAgentJarByCwdScanCanBeDisabled() throws Exception {
        String oldOverride = System.getProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
        String oldCp = System.getProperty("java.class.path");
        String oldUserDir = System.getProperty("user.dir");
        String oldAllow = System.getProperty(JarLocator.LOCATOR_ALLOW_CWD_SCAN_PROPERTY);

        File root = createTempDir("sleuth-locator-root-");
        File agentTarget = new File(root, "agent/target");
        assertTrue(agentTarget.mkdirs());

        File v100 = new File(agentTarget, "java-sleuth-agent-1.0.0" + JarLocator.DEFAULT_AGENT_JAR_SUFFIX);
        Map<String, String> mf = new HashMap<>();
        mf.put("Agent-Class", "com.javasleuth.agent.SleuthAgent");
        mf.put("Premain-Class", "com.javasleuth.agent.SleuthAgent");
        writeJarWithManifest(v100, mf);

        try {
            System.clearProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
            System.setProperty("java.class.path", "");
            System.setProperty("user.dir", root.getAbsolutePath());
            System.setProperty(JarLocator.LOCATOR_ALLOW_CWD_SCAN_PROPERTY, "false");

            File located = JarLocator.locateAgentJar(null);
            assertNull(located);
        } finally {
            restoreSysprop(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY, oldOverride);
            restoreSysprop("java.class.path", oldCp);
            restoreSysprop("user.dir", oldUserDir);
            restoreSysprop(JarLocator.LOCATOR_ALLOW_CWD_SCAN_PROPERTY, oldAllow);
        }
    }

    @Test
    public void testLocateAgentContainerJarFromCodeSourcePrefersStableSiblingName() throws Exception {
        String oldOverride = System.getProperty(JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY);

        File root = createTempDir("sleuth-locator-sibling-container-");
        File anchorJar = new File(root, "java-sleuth-launcher.jar");
        File stableContainerJar = new File(root, "java-sleuth-container.jar");
        anchorJar.deleteOnExit();
        stableContainerJar.deleteOnExit();

        writeJarWithSingleCompiledClass(anchorJar, "com.example.SleuthLocatorAnchor");

        Map<String, String> containerMf = new HashMap<>();
        containerMf.put(JarLocator.MANIFEST_MARKER_CONTAINER, "true");
        writeJarWithManifest(stableContainerJar, containerMf);

        try {
            System.clearProperty(JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY);
            try (URLClassLoader cl = new URLClassLoader(new URL[] { anchorJar.toURI().toURL() }, null)) {
                Class<?> anchor = Class.forName("com.example.SleuthLocatorAnchor", true, cl);
                File located = JarLocator.locateAgentContainerJar(anchor);
                assertNotNull(located);
                assertEquals(stableContainerJar.getAbsolutePath(), located.getAbsolutePath());
            }
        } finally {
            restoreSysprop(JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY, oldOverride);
        }
    }

    @Test
    public void testLocateAgentJarFromCodeSourcePrefersStableSiblingName() throws Exception {
        String oldOverride = System.getProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);

        File root = createTempDir("sleuth-locator-sibling-agent-");
        File anchorJar = new File(root, "java-sleuth-launcher.jar");
        File stableAgentJar = new File(root, "java-sleuth-agent.jar");
        anchorJar.deleteOnExit();
        stableAgentJar.deleteOnExit();

        writeJarWithSingleCompiledClass(anchorJar, "com.example.SleuthLocatorAnchor");

        Map<String, String> agentMf = new HashMap<>();
        agentMf.put("Agent-Class", "com.javasleuth.agent.SleuthAgent");
        agentMf.put("Premain-Class", "com.javasleuth.agent.SleuthAgent");
        agentMf.put(JarLocator.MANIFEST_MARKER_BOOTSTRAP, "true");
        writeJarWithManifest(stableAgentJar, agentMf);

        try {
            System.clearProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
            try (URLClassLoader cl = new URLClassLoader(new URL[] { anchorJar.toURI().toURL() }, null)) {
                Class<?> anchor = Class.forName("com.example.SleuthLocatorAnchor", true, cl);
                File located = JarLocator.locateAgentJar(anchor);
                assertNotNull(located);
                assertEquals(stableAgentJar.getAbsolutePath(), located.getAbsolutePath());
            }
        } finally {
            restoreSysprop(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY, oldOverride);
        }
    }

    private static void restoreSysprop(String key, String old) {
        if (old == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, old);
        }
    }

    private static File createTempDir(String prefix) throws Exception {
        File f = File.createTempFile(prefix, "");
        assertTrue(f.delete());
        assertTrue(f.mkdirs());
        f.deleteOnExit();
        return f;
    }

    private static void writeJarWithManifest(File file, Map<String, String> manifestEntries) throws Exception {
        Manifest mf = new Manifest();
        Attributes attrs = mf.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (manifestEntries != null) {
            for (Map.Entry<String, String> e : manifestEntries.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                attrs.putValue(e.getKey(), e.getValue());
            }
        }

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(file), mf)) {
            // no-op
        }
    }

    private static void writeJarWithSingleCompiledClass(File jarFile, String binaryName) throws Exception {
        assertNotNull(jarFile);
        assertNotNull(binaryName);

        File tmp = createTempDir("sleuth-locator-compile-");
        File srcRoot = new File(tmp, "src");
        File outRoot = new File(tmp, "classes");
        assertTrue(srcRoot.mkdirs());
        assertTrue(outRoot.mkdirs());

        String pkg = "";
        String simpleName = binaryName;
        int lastDot = binaryName.lastIndexOf('.');
        if (lastDot > 0) {
            pkg = binaryName.substring(0, lastDot);
            simpleName = binaryName.substring(lastDot + 1);
        }

        File pkgDir = srcRoot;
        if (!pkg.isEmpty()) {
            pkgDir = new File(srcRoot, pkg.replace('.', '/'));
            assertTrue(pkgDir.mkdirs());
        }
        File javaFile = new File(pkgDir, simpleName + ".java");

        StringBuilder src = new StringBuilder();
        if (!pkg.isEmpty()) {
            src.append("package ").append(pkg).append(";\n");
        }
        src.append("public class ").append(simpleName).append(" { }\n");

        try (FileWriter fw = new FileWriter(javaFile)) {
            fw.write(src.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("JDK required: ToolProvider.getSystemJavaCompiler() returned null", compiler);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
        List<String> options = Arrays.asList("-d", outRoot.getAbsolutePath());
        Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjects(javaFile);

        Boolean ok;
        try {
            ok = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
        } finally {
            fileManager.close();
        }

        if (!Boolean.TRUE.equals(ok)) {
            StringBuilder msg = new StringBuilder("Failed to compile test anchor class: ").append(binaryName).append("\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                msg.append("  - ").append(d.toString()).append("\n");
            }
            fail(msg.toString());
        }

        Manifest mf = new Manifest();
        Attributes attrs = mf.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");

        String classEntryName = binaryName.replace('.', '/') + ".class";
        File classFile = new File(outRoot, classEntryName);
        assertTrue("Expected compiled class file missing: " + classFile.getAbsolutePath(), classFile.isFile());

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile), mf)) {
            JarEntry entry = new JarEntry(classEntryName);
            jos.putNextEntry(entry);
            Files.copy(classFile.toPath(), jos);
            jos.closeEntry();
        }
    }
}
