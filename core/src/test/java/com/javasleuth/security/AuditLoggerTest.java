package com.javasleuth.foundation.security;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class AuditLoggerTest {

    @Test
    public void testAuthArgsAreMaskedInAuditFormatting() throws Exception {
        AuditLogger logger = AuditLogger.getInstance();
        String formatted = formatArgsForAudit(logger, "auth", new String[]{"auth", "admin", "my_password"});
        assertNotNull(formatted);
        assertFalse(formatted.contains("my_password"));
        assertTrue(formatted.contains("***"));
    }

    @Test
    public void testConfigSetSensitiveValueIsMaskedInAuditFormatting() throws Exception {
        AuditLogger logger = AuditLogger.getInstance();
        String formatted = formatArgsForAudit(logger, "config", new String[]{"config", "set", "security.hmac.secret", "supersecret"});
        assertNotNull(formatted);
        assertFalse(formatted.contains("supersecret"));
        assertTrue(formatted.contains("***"));
    }

    @Test
    public void testSyspropSetSensitiveValueIsMaskedInAuditFormatting() throws Exception {
        AuditLogger logger = AuditLogger.getInstance();
        String formatted = formatArgsForAudit(logger, "sysprop", new String[]{"sysprop", "set", "api.token", "tok_123456"});
        assertNotNull(formatted);
        assertFalse(formatted.contains("tok_123456"));
        assertTrue(formatted.contains("***"));
    }

    @Test
    public void testSessionIdIsMasked() throws Exception {
        AuditLogger logger = AuditLogger.getInstance();

        Method m = AuditLogger.class.getDeclaredMethod("maskSessionId", String.class);
        m.setAccessible(true);

        String token = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        String masked = (String) m.invoke(logger, token);
        assertNotNull(masked);
        assertNotEquals(token, masked);
        assertFalse(masked.contains(token));
        assertTrue(masked.contains("***"));
    }

    @Test
    public void testAuditConsoleMirrorDoesNotPolluteStdout() throws Exception {
        String oldAuditConsole = System.getProperty("sleuth.logging.audit.console.enabled");
        String oldConsole = System.getProperty("sleuth.logging.console.enabled");

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;

        try {
            System.setProperty("sleuth.logging.audit.console.enabled", "true");
            System.setProperty("sleuth.logging.console.enabled", "true");

            System.setOut(new PrintStream(outBuf));
            System.setErr(new PrintStream(errBuf));

            AuditLogger logger = AuditLogger.getInstance();

            Class<?> eventClass = Class.forName("com.javasleuth.foundation.security.AuditLogger$AuditEvent");
            Constructor<?> ctor = eventClass.getDeclaredConstructor(
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class
            );
            ctor.setAccessible(true);
            Object event = ctor.newInstance("INFO", "SYSTEM", "TEST", "hello", null, "system");

            Method write = AuditLogger.class.getDeclaredMethod("writeAuditEvent", eventClass);
            write.setAccessible(true);
            write.invoke(logger, event);
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
            setOrClearProperty("sleuth.logging.audit.console.enabled", oldAuditConsole);
            setOrClearProperty("sleuth.logging.console.enabled", oldConsole);
        }

        String out = outBuf.toString("UTF-8");
        String err = errBuf.toString("UTF-8");
        assertFalse(out.contains("AUDIT:"));
        assertTrue(err.contains("AUDIT:"));
        assertTrue(err.contains("SLEUTH:"));
    }

    private static String formatArgsForAudit(AuditLogger logger, String command, String[] args) throws Exception {
        Method m = AuditLogger.class.getDeclaredMethod("formatArgsForAudit", String.class, String[].class);
        m.setAccessible(true);
        return (String) m.invoke(logger, command, args);
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
