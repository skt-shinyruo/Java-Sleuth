package com.javasleuth.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class JvmUtilsTest {

    @Test
    public void testFormatSize() {
        assertEquals("100 B", JvmUtils.formatSize(100));
        assertEquals("1.00 KB", JvmUtils.formatSize(1024));
        assertEquals("1.00 MB", JvmUtils.formatSize(1024 * 1024));
        assertEquals("1.00 GB", JvmUtils.formatSize(1024L * 1024 * 1024));
    }

    @Test
    public void testFormatDuration() {
        assertEquals("1s", JvmUtils.formatDuration(1000));
        assertEquals("1m 30s", JvmUtils.formatDuration(90000));
        assertEquals("1h 01m 30s", JvmUtils.formatDuration(3690000));
    }

    @Test
    public void testGetCurrentJvmPid() {
        String pid = JvmUtils.getCurrentJvmPid();
        assertNotNull(pid);
        assertFalse(pid.isEmpty());
        assertTrue(pid.matches("\\d+"));
    }

    @Test
    public void testGetCurrentJvmDisplayName() {
        String displayName = JvmUtils.getCurrentJvmDisplayName();
        assertNotNull(displayName);
        assertFalse(displayName.isEmpty());
    }
}