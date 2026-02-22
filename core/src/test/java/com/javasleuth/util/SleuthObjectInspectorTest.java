package com.javasleuth.core.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class SleuthObjectInspectorTest {
    private static class Base {
        private final String baseField = "base";
    }

    private static class Target extends Base {
        private final String name = "n";
        private final String password = "secret";
        private static final String staticField = "STATIC";
    }

    @Test
    public void inspectMasksSensitiveFieldsAndSkipsStaticByDefault() {
        String out = SleuthObjectInspector.inspect(new Target(), new SleuthObjectInspector.Options()
            .withMaxDepth(1)
            .withMaxFields(50)
            .withIncludeStatic(false)
            .withIncludeInherited(true)
        );

        assertNotNull(out);
        assertTrue(out.contains("password"));
        assertTrue(out.contains("\"****\""));
        assertFalse(out.contains("staticField"));
        assertTrue(out.contains("baseField"));
    }
}
