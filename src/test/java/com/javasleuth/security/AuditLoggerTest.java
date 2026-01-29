package com.javasleuth.security;

import org.junit.Test;

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

    private static String formatArgsForAudit(AuditLogger logger, String command, String[] args) throws Exception {
        Method m = AuditLogger.class.getDeclaredMethod("formatArgsForAudit", String.class, String[].class);
        m.setAccessible(true);
        return (String) m.invoke(logger, command, args);
    }
}

