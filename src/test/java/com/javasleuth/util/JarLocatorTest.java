package com.javasleuth.util;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class JarLocatorTest {

    @Test
    public void testLocateAgentJarByOverrideProperty() throws Exception {
        String old = System.getProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
        File tmp = File.createTempFile("sleuth-agent-", ".jar");
        tmp.deleteOnExit();

        try {
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
}

