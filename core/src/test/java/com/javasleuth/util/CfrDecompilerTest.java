package com.javasleuth.util;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.*;

public class CfrDecompilerTest {

    public static class Sample {
        public int add(int a, int b) {
            return a + b;
        }
    }

    @Test
    public void decompileClassBytes_producesNonEmptySource() throws Exception {
        byte[] bytes = readClassBytes(Sample.class);
        String out = CfrDecompiler.decompileClassBytes(Sample.class.getName(), bytes, false, false);
        assertNotNull(out);
        assertTrue(out.contains("class"));
        assertTrue(out.contains("add"));
    }

    private static byte[] readClassBytes(Class<?> c) throws IOException {
        String resource = c.getName().replace('.', '/') + ".class";
        ClassLoader cl = c.getClassLoader();
        InputStream is = cl != null ? cl.getResourceAsStream(resource) : ClassLoader.getSystemResourceAsStream(resource);
        assertNotNull("missing class resource: " + resource, is);
        try (InputStream in = is; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }
}

