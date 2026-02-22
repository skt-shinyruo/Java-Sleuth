package com.javasleuth.bootstrap.util;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
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
    public void testLocateAgentCoreJarByOverrideProperty() throws Exception {
        String old = System.getProperty(JarLocator.AGENT_CORE_JAR_OVERRIDE_PROPERTY);
        File tmp = File.createTempFile("sleuth-agent-core-", ".jar");
        tmp.deleteOnExit();

        try {
            Map<String, String> mf = new HashMap<>();
            mf.put(JarLocator.MANIFEST_MARKER_CORE, "true");
            writeJarWithManifest(tmp, mf);

            System.setProperty(JarLocator.AGENT_CORE_JAR_OVERRIDE_PROPERTY, tmp.getAbsolutePath());
            File located = JarLocator.locateAgentCoreJar(JarLocatorTest.class);
            assertNotNull(located);
            assertEquals(tmp.getAbsolutePath(), located.getAbsolutePath());
        } finally {
            if (old == null) {
                System.clearProperty(JarLocator.AGENT_CORE_JAR_OVERRIDE_PROPERTY);
            } else {
                System.setProperty(JarLocator.AGENT_CORE_JAR_OVERRIDE_PROPERTY, old);
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
}
