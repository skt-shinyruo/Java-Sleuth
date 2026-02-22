package com.javasleuth.core.vmtool;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class VmToolMethodInvokerTest {
    public static class Target {
        public int add(int a, int b) {
            return a + b;
        }

        public String add(String a, String b) {
            return String.valueOf(a) + String.valueOf(b);
        }

        public String echo(String s) {
            return s;
        }

        public String nullable(String s) {
            return s;
        }

        public int primitive(int v) {
            return v;
        }
    }

    @Test
    public void invokeInstance_prefersNumericOverStringOverload() throws Exception {
        Target t = new Target();
        Object r = VmToolMethodInvoker.invokeInstance(t, "add", Arrays.asList("1", "2"), false, true);
        assertTrue(r instanceof Integer);
        assertEquals(3, ((Integer) r).intValue());
    }

    @Test
    public void invokeInstance_fallsBackToStringOverload() throws Exception {
        Target t = new Target();
        Object r = VmToolMethodInvoker.invokeInstance(t, "add", Arrays.asList("a", "b"), false, true);
        assertEquals("ab", r);
    }

    @Test
    public void invokeInstance_allowsNullForReferenceTypes() throws Exception {
        Target t = new Target();
        Object r = VmToolMethodInvoker.invokeInstance(t, "nullable", Collections.singletonList("null"), false, true);
        assertNull(r);
    }

    @Test
    public void invokeInstance_rejectsNullForPrimitiveTypes() {
        Target t = new Target();
        try {
            VmToolMethodInvoker.invokeInstance(t, "primitive", Collections.singletonList("null"), false, true);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        } catch (Exception other) {
            fail("Unexpected exception: " + other);
        }
    }

    @Test
    public void forbidDangerousTargets_withoutUnsafe() {
        try {
            VmToolMethodInvoker.resolveInvocation(Runtime.class, false, "getRuntime", Collections.emptyList(), false, false);
            fail("Expected SecurityException");
        } catch (SecurityException expected) {
            // ok
        }
    }
}
